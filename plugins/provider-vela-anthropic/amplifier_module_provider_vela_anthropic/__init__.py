"""Vela streaming Anthropic provider module.

Extends amplifier-module-provider-anthropic with a real stream() method so
loop-vela can emit per-token content_block:delta events from actual Anthropic
API streaming rather than simulation.
"""

__amplifier_module_type__ = "provider"

import asyncio
import logging
import os
from collections.abc import AsyncIterator
from typing import Any

from amplifier_module_provider_anthropic import AnthropicProvider

logger = logging.getLogger(__name__)


class VelaAnthropicProvider(AnthropicProvider):
    """AnthropicProvider with real streaming support.

    The base provider only has complete(). This subclass adds stream() which
    yields individual token dicts from the Anthropic SDK's streaming API.
    loop-vela checks hasattr(provider, "stream") and uses this path when True.
    """

    async def stream(
        self,
        request: Any,
        tools: Any = None,
        **kwargs: Any,
    ) -> AsyncIterator[dict[str, Any]]:
        """Yield real tokens from Anthropic streaming API.

        Chunk format matches what loop-vela's _stream_from_provider expects:
          - Text token:  {"content": "...", "block_type": "text"}
          - Thinking:    {"block_type": "thinking", "content": "..."}
          - Signature:   {"block_type": "thinking", "signature": "..."}
          - Tool start:  {"block_type": "tool_use", "tool_id": "...", "tool_name": "...", "content": ""}
          - Tool delta:  {"block_type": "tool_use", "content": "<partial json>"}
        """
        # Separate messages by role — mirrors _complete_chat_request logic
        messages = list(request.messages)
        system_msgs = [m for m in messages if m.role == "system"]
        developer_msgs = [m for m in messages if m.role == "developer"]
        conversation = [m for m in messages if m.role in ("user", "assistant", "tool")]

        # System prompt via parent's formatter
        system_blocks = self._format_system_with_cache(system_msgs) if system_msgs else None

        # Developer messages → XML-wrapped user turns (same as parent)
        context_user_msgs = [
            {"role": "user", "content": f"<context_file>\n{m.content}\n</context_file>"}
            for m in developer_msgs
        ]

        # Use the same model_dump() approach as the parent's _complete_chat_request.
        # _convert_messages() requires model_dump() format to correctly access
        # tool_calls / tool_call_id fields when batching tool results into a single
        # user message — manually building dicts loses those fields, causing the LLM
        # to never receive tool results and loop indefinitely.
        conv_dicts = [m.model_dump() for m in conversation]
        all_msgs = context_user_msgs + self._convert_messages(conv_dicts)

        # Model + max_tokens
        model = kwargs.get("model", self.default_model)
        max_tokens = (
            kwargs.get("max_tokens")
            or self.config.get("max_tokens")
            or 8192
        )

        params: dict[str, Any] = {
            "model": model,
            "max_tokens": max_tokens,
            "messages": all_msgs,
        }
        if system_blocks:
            params["system"] = system_blocks

        # Tools — use parent's converter if available
        if request.tools and hasattr(self, "_convert_tools_from_request"):
            params["tools"] = self._convert_tools_from_request(request.tools)

        logger.info(
            "[VELA-PROVIDER] stream() — model: %s, messages: %d",
            model,
            len(all_msgs),
        )

        async with asyncio.timeout(self.timeout):
            async with self.client.messages.stream(**params) as stream_ctx:
                async for event in stream_ctx:
                    if not hasattr(event, "type"):
                        continue

                    if event.type == "content_block_delta":
                        delta = event.delta
                        dtype = getattr(delta, "type", "")
                        if dtype == "text_delta":
                            yield {"content": delta.text, "block_type": "text"}
                        elif dtype == "thinking_delta":
                            yield {
                                "block_type": "thinking",
                                "content": getattr(delta, "thinking", ""),
                            }
                        elif dtype == "signature_delta":
                            yield {
                                "block_type": "thinking",
                                "signature": getattr(delta, "signature", ""),
                            }
                        elif dtype == "input_json_delta":
                            yield {
                                "block_type": "tool_use",
                                "content": getattr(delta, "partial_json", ""),
                            }

                    elif event.type == "content_block_start":
                        block = event.content_block
                        btype = getattr(block, "type", "")
                        if btype == "tool_use":
                            yield {
                                "block_type": "tool_use",
                                "tool_id": getattr(block, "id", ""),
                                "tool_name": getattr(block, "name", ""),
                                "content": "",
                            }


async def mount(
    coordinator: Any,
    config: dict[str, Any] | None = None,
) -> Any:
    """Mount the Vela streaming Anthropic provider."""
    config = config or {}

    api_key = config.get("api_key") or os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        logger.warning("[VELA-PROVIDER] No ANTHROPIC_API_KEY — provider not mounted")
        return None

    provider = VelaAnthropicProvider(api_key, config, coordinator)
    await coordinator.mount("providers", provider, name="anthropic")
    logger.info("[VELA-PROVIDER] VelaAnthropicProvider mounted with stream() support")

    async def cleanup() -> None:
        await provider.close()

    return cleanup
