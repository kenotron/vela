"""
loop-vela orchestrator for Amplifier.

Based on loop-streaming, adds:
  - content_block:delta events per token via provider.stream() — no simulation
  - steer-inject: user can inject a message mid-loop via _steer_queues
  - Proper content_block:start / content_block:end during the streaming path
  - No artificial stream_delay

Steer API (in-process IPC):
  from amplifier_module_loop_vela import _steer_queues
  await _steer_queues[session_id].put("redirect message")

New SSE events emitted:
  content_block:delta  {token, block_index}
  steer:applied        {message}
"""

# Amplifier module metadata
__amplifier_module_type__ = "orchestrator"

import asyncio
import json
import logging
import time
from collections.abc import AsyncIterator
from typing import Any

from amplifier_core import HookRegistry
from amplifier_core import ModuleCoordinator
from amplifier_core import ToolResult
from amplifier_core.events import CANCEL_COMPLETED
from amplifier_core.events import CANCEL_REQUESTED
from amplifier_core.events import CONTENT_BLOCK_END
from amplifier_core.events import CONTENT_BLOCK_START
from amplifier_core.events import ORCHESTRATOR_COMPLETE
from amplifier_core.events import PROMPT_SUBMIT
from amplifier_core.events import PROVIDER_ERROR
from amplifier_core.events import PROVIDER_REQUEST
from amplifier_core.events import TOOL_ERROR
from amplifier_core.events import TOOL_POST
from amplifier_core.events import TOOL_PRE
from amplifier_core.llm_errors import LLMError
from amplifier_core.message_models import ChatRequest
from amplifier_core.message_models import Message
from amplifier_core.message_models import ToolSpec

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Process-global steer queue registry.
# Key: session_id (str).  Value: asyncio.Queue[str]
# The vela_plugin.steer endpoint imports this dict to enqueue steering messages.
# ---------------------------------------------------------------------------
_steer_queues: dict[str, asyncio.Queue] = {}

CONTENT_BLOCK_DELTA = "content_block:delta"


async def mount(coordinator: ModuleCoordinator, config: dict[str, Any] | None = None):
    """Mount the loop-vela orchestrator."""
    config = config or {}

    coordinator.register_contributor(
        "observability.events",
        "loop-vela",
        lambda: [
            "execution:start",
            "execution:end",
            "content_block:delta",
            "steer:applied",
        ],
    )

    # Resolve a stable session key for the steer queue
    session_id: str = getattr(coordinator, "session_id", None) or str(id(coordinator))

    steer_queue: asyncio.Queue = asyncio.Queue()
    _steer_queues[session_id] = steer_queue

    orchestrator = VelaOrchestrator(
        config, steer_queue=steer_queue, session_id=session_id
    )
    await coordinator.mount("orchestrator", orchestrator)
    logger.info("loop-vela mounted (session_id=%s)", session_id)

    def cleanup():
        _steer_queues.pop(session_id, None)

    return cleanup


class VelaOrchestrator:
    """
    loop-vela: streaming orchestrator with per-token delta events and steer-inject.
    """

    def __init__(
        self,
        config: dict[str, Any],
        steer_queue: asyncio.Queue | None = None,
        session_id: str | None = None,
    ):
        self.config = config
        max_iter_config = config.get("max_iterations", -1)
        self.max_iterations = int(max_iter_config) if max_iter_config != -1 else -1
        self.extended_thinking = config.get("extended_thinking", False)
        self.min_delay_between_calls_ms = config.get("min_delay_between_calls_ms", 0)
        self._last_provider_call_end: float | None = None
        self._pending_ephemeral_injections: list[dict[str, Any]] = []
        self._cancel_requested_emitted: bool = False
        self._steer_queue = steer_queue
        self._session_id = session_id

    async def _apply_rate_limit_delay(
        self, hooks: HookRegistry, iteration: int
    ) -> None:
        if self.min_delay_between_calls_ms <= 0:
            return
        if self._last_provider_call_end is None:
            return
        elapsed_ms = (time.monotonic() - self._last_provider_call_end) * 1000
        remaining_ms = self.min_delay_between_calls_ms - elapsed_ms
        if remaining_ms > 0:
            await hooks.emit(
                "orchestrator:rate_limit_delay",
                {
                    "delay_ms": remaining_ms,
                    "configured_ms": self.min_delay_between_calls_ms,
                    "elapsed_ms": elapsed_ms,
                    "iteration": iteration,
                },
            )
            await asyncio.sleep(remaining_ms / 1000)

    async def execute(
        self,
        prompt: str,
        context,
        providers: dict[str, Any],
        tools: dict[str, Any],
        hooks: HookRegistry,
        coordinator: ModuleCoordinator | None = None,
    ) -> str:
        self._cancel_requested_emitted = False
        full_response = ""
        iteration_count = 0
        error: Exception | None = None

        try:
            async for token, iteration in self._execute_stream(
                prompt, context, providers, tools, hooks, coordinator
            ):
                full_response += token
                iteration_count = iteration
        except Exception as e:
            error = e

        if error:
            status = "error"
        elif coordinator and coordinator.cancellation.is_cancelled:
            status = "cancelled"
        else:
            status = "success" if full_response else "incomplete"

        await hooks.emit(
            ORCHESTRATOR_COMPLETE,
            {
                "orchestrator": "loop-vela",
                "turn_count": iteration_count,
                "status": status,
            },
        )

        if error:
            raise error

        return full_response

    async def _execute_stream(
        self,
        prompt: str,
        context,
        providers: dict[str, Any],
        tools: dict[str, Any],
        hooks: HookRegistry,
        coordinator: ModuleCoordinator | None = None,
    ) -> AsyncIterator[tuple[str, int]]:
        # Prompt submit hook
        prompt_submit_result = await hooks.emit(PROMPT_SUBMIT, {"prompt": prompt})
        if coordinator:
            prompt_submit_result = await coordinator.process_hook_result(
                prompt_submit_result, "prompt:submit", "orchestrator"
            )
            if prompt_submit_result.action == "deny":
                yield (f"Operation denied: {prompt_submit_result.reason}", 0)
                return

        if (
            prompt_submit_result.action == "inject_context"
            and prompt_submit_result.ephemeral
            and prompt_submit_result.context_injection
        ):
            self._pending_ephemeral_injections.append(
                {
                    "role": prompt_submit_result.context_injection_role,
                    "content": prompt_submit_result.context_injection,
                    "append_to_last_tool_result": prompt_submit_result.append_to_last_tool_result,
                }
            )

        await hooks.emit("execution:start", {"prompt": prompt})
        self._last_provider_call_end = None

        await context.add_message({"role": "user", "content": prompt})

        provider = self._select_provider(providers)
        if not provider:
            yield ("Error: No providers available", 0)
            return

        provider_name = None
        for name, prov in providers.items():
            if prov is provider:
                provider_name = name
                break

        iteration = 0
        block_index = 0  # increments per streaming text block

        while self.max_iterations == -1 or iteration < self.max_iterations:
            # Cancellation check
            if coordinator and coordinator.cancellation.is_cancelled:
                if not self._cancel_requested_emitted:
                    self._cancel_requested_emitted = True
                    await hooks.emit(
                        CANCEL_REQUESTED,
                        {
                            "orchestrator": "loop-vela",
                            "state": str(coordinator.cancellation.state),
                            "turn_count": iteration,
                        },
                    )
                    try:
                        await coordinator.cancellation.trigger_callbacks()
                    except Exception as e:
                        logger.warning("Error in cancellation callbacks: %s", e)
                await hooks.emit(
                    CANCEL_COMPLETED,
                    {
                        "orchestrator": "loop-vela",
                        "was_immediate": coordinator.cancellation.is_immediate,
                        "turn_count": iteration,
                    },
                )
                return

            iteration += 1

            # Provider request hook
            result = await hooks.emit(
                PROVIDER_REQUEST, {"provider": provider_name, "iteration": iteration}
            )
            if coordinator:
                result = await coordinator.process_hook_result(
                    result, "provider:request", "orchestrator"
                )
                if result.action == "deny":
                    yield (f"Operation denied: {result.reason}", iteration)
                    return

            message_dicts = await context.get_messages_for_request(provider=provider)
            message_dicts = list(message_dicts)

            # Ephemeral injection from provider:request hook
            if (
                result.action == "inject_context"
                and result.ephemeral
                and result.context_injection
            ):
                if result.append_to_last_tool_result and len(message_dicts) > 0:
                    last_msg = message_dicts[-1]
                    if last_msg.get("role") == "tool":
                        original_content = last_msg.get("content", "")
                        message_dicts[-1] = {
                            **last_msg,
                            "content": f"{original_content}\n\n{result.context_injection}",
                        }
                    else:
                        message_dicts.append(
                            {
                                "role": result.context_injection_role,
                                "content": result.context_injection,
                            }
                        )
                else:
                    message_dicts.append(
                        {
                            "role": result.context_injection_role,
                            "content": result.context_injection,
                        }
                    )

            # Apply pending ephemeral injections from tool:post hooks
            if self._pending_ephemeral_injections:
                for injection in self._pending_ephemeral_injections:
                    if (
                        injection.get("append_to_last_tool_result")
                        and len(message_dicts) > 0
                    ):
                        last_msg = message_dicts[-1]
                        if last_msg.get("role") == "tool":
                            original_content = last_msg.get("content", "")
                            message_dicts[-1] = {
                                **last_msg,
                                "content": f"{original_content}\n\n{injection['content']}",
                            }
                        else:
                            message_dicts.append(
                                {
                                    "role": injection["role"],
                                    "content": injection["content"],
                                }
                            )
                    else:
                        message_dicts.append(
                            {"role": injection["role"], "content": injection["content"]}
                        )
                self._pending_ephemeral_injections = []

            messages_objects = [Message(**msg) for msg in message_dicts]

            tools_list = None
            if tools:
                tools_list = [
                    ToolSpec(
                        name=t.name,
                        description=t.description,
                        parameters=t.input_schema,
                    )
                    for t in tools.values()
                ]

            chat_request = ChatRequest(
                messages=messages_objects,
                tools=tools_list,
                reasoning_effort=self.config.get("reasoning_effort"),
            )

            await self._apply_rate_limit_delay(hooks, iteration)

            if hasattr(provider, "stream"):
                async for chunk in self._stream_from_provider(
                    provider,
                    chat_request,
                    context,
                    tools,
                    hooks,
                    coordinator,
                    provider_name=provider_name,
                    block_index=block_index,
                ):
                    if coordinator and coordinator.cancellation.is_immediate:
                        return
                    yield (chunk, iteration)

                block_index += 1
                self._last_provider_call_end = time.monotonic()

                if await self._has_pending_tools(context):
                    await self._process_tools(context, tools, hooks)

                    # ── Drain steer queue between iterations ──────────────────
                    await self._drain_steer_queue(context, hooks)
                    continue
                else:
                    break
            else:
                # Non-streaming fallback
                kwargs = {}
                if self.extended_thinking:
                    kwargs["extended_thinking"] = True
                try:
                    response = await provider.complete(chat_request, **kwargs)
                except LLMError as e:
                    await hooks.emit(
                        PROVIDER_ERROR,
                        {
                            "provider": provider_name,
                            "error": {"type": type(e).__name__, "msg": str(e)},
                            "retryable": e.retryable,
                            "status_code": e.status_code,
                        },
                    )
                    raise
                except Exception as e:
                    await hooks.emit(
                        PROVIDER_ERROR,
                        {
                            "provider": provider_name,
                            "error": {"type": type(e).__name__, "msg": str(e)},
                        },
                    )
                    raise

                self._last_provider_call_end = time.monotonic()

                content_blocks = getattr(response, "content_blocks", None)
                if content_blocks:
                    total_blocks = len(content_blocks)
                    for idx, block in enumerate(content_blocks):
                        await hooks.emit(
                            CONTENT_BLOCK_START,
                            {
                                "block_type": block.type.value,
                                "block_index": idx,
                                "total_blocks": total_blocks,
                                "metadata": getattr(block, "raw", None),
                            },
                        )
                        event_data = {
                            "block_index": idx,
                            "total_blocks": total_blocks,
                            "block": block.to_dict(),
                        }
                        if response.usage:
                            event_data["usage"] = response.usage.model_dump()
                        await hooks.emit(CONTENT_BLOCK_END, event_data)
                elif response.content and isinstance(response.content, list):
                    total_blocks = len(response.content)
                    for idx, block in enumerate(response.content):
                        block_dict = (
                            block.model_dump()
                            if hasattr(block, "model_dump")
                            else block
                        )
                        block_type = (
                            block_dict.get("type", "text")
                            if isinstance(block_dict, dict)
                            else "text"
                        )
                        await hooks.emit(
                            CONTENT_BLOCK_START,
                            {
                                "block_type": block_type,
                                "block_index": idx,
                                "total_blocks": total_blocks,
                            },
                        )
                        event_data = {
                            "block_index": idx,
                            "total_blocks": total_blocks,
                            "block": block_dict,
                        }
                        if response.usage:
                            event_data["usage"] = response.usage.model_dump()
                        await hooks.emit(CONTENT_BLOCK_END, event_data)

                tool_calls = provider.parse_tool_calls(response)

                if not tool_calls:
                    if hasattr(response, "text") and response.text:
                        response_text = response.text
                    else:
                        response_text = self._extract_text_from_content(
                            response.content
                        )

                    # content_block:start / end already emitted above — just
                    # yield the full text for accumulation; no per-token simulation.
                    yield (response_text, iteration)
                    block_index += 1

                    response_content = getattr(response, "content", None)
                    if response_content and isinstance(response_content, list):
                        content_dicts = [
                            block.model_dump()
                            if hasattr(block, "model_dump")
                            else block
                            for block in response_content
                        ]
                        assistant_msg = {
                            "role": "assistant",
                            "content": content_dicts,
                        }
                    else:
                        assistant_msg = {
                            "role": "assistant",
                            "content": response_text,
                        }

                    if response_content and isinstance(response_content, list):
                        for block in response_content:
                            block_type = getattr(block, "type", None)
                            type_value = (
                                getattr(block_type, "value", block_type)
                                if block_type
                                else None
                            )
                            if type_value == "thinking":
                                assistant_msg["thinking_block"] = (
                                    block.model_dump()
                                    if hasattr(block, "model_dump")
                                    else None
                                )
                                break

                    if hasattr(response, "metadata") and response.metadata:
                        assistant_msg["metadata"] = response.metadata

                    await context.add_message(assistant_msg)
                    break

                if hasattr(response, "text") and response.text:
                    response_text = response.text
                else:
                    response_text = (
                        self._extract_text_from_content(response.content)
                        if response.content
                        else ""
                    )

                response_content = getattr(response, "content", None)
                if response_content and isinstance(response_content, list):
                    assistant_msg = {
                        "role": "assistant",
                        "content": [
                            block.model_dump()
                            if hasattr(block, "model_dump")
                            else block
                            for block in response_content
                        ],
                        "tool_calls": [
                            {
                                "id": tc.id,
                                "tool": tc.name,
                                "arguments": tc.arguments,
                            }
                            for tc in tool_calls
                        ],
                    }
                else:
                    assistant_msg = {
                        "role": "assistant",
                        "content": response_text,
                        "tool_calls": [
                            {
                                "id": tc.id,
                                "tool": tc.name,
                                "arguments": tc.arguments,
                            }
                            for tc in tool_calls
                        ],
                    }

                if response_content and isinstance(response_content, list):
                    for block in response_content:
                        block_type = getattr(block, "type", None)
                        type_value = (
                            getattr(block_type, "value", block_type)
                            if block_type
                            else None
                        )
                        if type_value == "thinking":
                            assistant_msg["thinking_block"] = (
                                block.model_dump()
                                if hasattr(block, "model_dump")
                                else None
                            )
                            break

                if hasattr(response, "metadata") and response.metadata:
                    assistant_msg["metadata"] = response.metadata

                await context.add_message(assistant_msg)

                import uuid

                parallel_group_id = str(uuid.uuid4())
                tool_tasks = [
                    self._execute_tool_only(
                        tc, tools, hooks, parallel_group_id, coordinator
                    )
                    for tc in tool_calls
                ]

                try:
                    tool_results = await asyncio.gather(*tool_tasks)
                except asyncio.CancelledError:
                    for tc in tool_calls:
                        await context.add_message(
                            {
                                "role": "tool",
                                "name": tc.name,
                                "tool_call_id": tc.id,
                                "content": f'{{"error": "Tool execution was cancelled by user", "cancelled": true, "tool": "{tc.name}"}}',
                            }
                        )
                    if coordinator and not self._cancel_requested_emitted:
                        self._cancel_requested_emitted = True
                        await hooks.emit(
                            CANCEL_REQUESTED,
                            {
                                "orchestrator": "loop-vela",
                                "state": str(coordinator.cancellation.state),
                                "turn_count": iteration,
                            },
                        )
                        try:
                            await coordinator.cancellation.trigger_callbacks()
                        except Exception as e:
                            logger.warning("Error in cancellation callbacks: %s", e)
                    if coordinator:
                        await hooks.emit(
                            CANCEL_COMPLETED,
                            {
                                "orchestrator": "loop-vela",
                                "was_immediate": coordinator.cancellation.is_immediate,
                                "turn_count": iteration,
                            },
                        )
                    await context.add_message(
                        {
                            "role": "assistant",
                            "content": "The previous operation was cancelled. Results from completed tools have been preserved.",
                        }
                    )
                    raise

                if coordinator and coordinator.cancellation.is_cancelled:
                    for tool_call_id, tool_name, content in tool_results:
                        await context.add_message(
                            {
                                "role": "tool",
                                "name": tool_name,
                                "tool_call_id": tool_call_id,
                                "content": content,
                            }
                        )
                    if not self._cancel_requested_emitted:
                        self._cancel_requested_emitted = True
                        await hooks.emit(
                            CANCEL_REQUESTED,
                            {
                                "orchestrator": "loop-vela",
                                "state": str(coordinator.cancellation.state),
                                "turn_count": iteration,
                            },
                        )
                        try:
                            await coordinator.cancellation.trigger_callbacks()
                        except Exception as e:
                            logger.warning("Error in cancellation callbacks: %s", e)
                    await hooks.emit(
                        CANCEL_COMPLETED,
                        {
                            "orchestrator": "loop-vela",
                            "was_immediate": coordinator.cancellation.is_immediate,
                            "turn_count": iteration,
                        },
                    )
                    await context.add_message(
                        {
                            "role": "assistant",
                            "content": "The previous operation was cancelled. Results from completed tools have been preserved.",
                        }
                    )
                    return

                for tool_call_id, tool_name, content in tool_results:
                    await context.add_message(
                        {
                            "role": "tool",
                            "name": tool_name,
                            "tool_call_id": tool_call_id,
                            "content": content,
                        }
                    )

                # ── Drain steer queue between iterations ──────────────────────
                await self._drain_steer_queue(context, hooks)

        # Max iterations reached
        if self.max_iterations != -1 and iteration >= self.max_iterations:
            logger.warning("Max iterations (%d) reached", self.max_iterations)
            await hooks.emit(
                PROVIDER_REQUEST,
                {
                    "provider": provider_name,
                    "iteration": iteration,
                    "max_reached": True,
                },
            )
            message_dicts = await context.get_messages_for_request(provider=provider)
            message_dicts = list(message_dicts)
            message_dicts.append(
                {
                    "role": "user",
                    "content": """<system-reminder source="orchestrator-loop-limit">
You have reached the maximum number of iterations for this turn. Please provide a response to the user now, summarizing your progress and noting what remains to be done. You can continue in the next turn if needed.

DO NOT mention this iteration limit or reminder to the user explicitly. Simply wrap up naturally.
</system-reminder>""",
                }
            )
            try:
                messages_objects = [Message(**msg) for msg in message_dicts]
                tools_list = None
                if tools:
                    tools_list = [
                        ToolSpec(
                            name=t.name,
                            description=t.description,
                            parameters=t.input_schema,
                        )
                        for t in tools.values()
                    ]
                max_iter_chat_request = ChatRequest(
                    messages=messages_objects,
                    tools=tools_list,
                    reasoning_effort=self.config.get("reasoning_effort"),
                )
                if hasattr(provider, "stream"):
                    # Real streaming — emits content_block:start/delta/end and
                    # adds the assistant message to context automatically.
                    async for chunk in self._stream_from_provider(
                        provider,
                        max_iter_chat_request,
                        context,
                        tools,
                        hooks,
                        coordinator,
                        provider_name=provider_name,
                        block_index=block_index,
                    ):
                        if coordinator and coordinator.cancellation.is_immediate:
                            return
                        yield (chunk, iteration)
                    block_index += 1
                else:
                    # Non-streaming fallback — no simulation.
                    kwargs = {}
                    if self.extended_thinking:
                        kwargs["extended_thinking"] = True
                    response = await provider.complete(max_iter_chat_request, **kwargs)
                    content = (
                        response.content if hasattr(response, "content") else str(response)
                    )
                    if content:
                        content_str = content if isinstance(content, str) else str(content)
                        yield (content_str, iteration)
                        await context.add_message({"role": "assistant", "content": content_str})
            except LLMError as e:
                await hooks.emit(
                    PROVIDER_ERROR,
                    {
                        "provider": provider_name,
                        "error": {"type": type(e).__name__, "msg": str(e)},
                        "retryable": e.retryable,
                        "status_code": e.status_code,
                    },
                )
                logger.error("Error getting final response after max iterations: %s", e)
            except Exception as e:
                await hooks.emit(
                    PROVIDER_ERROR,
                    {
                        "provider": provider_name,
                        "error": {"type": type(e).__name__, "msg": str(e)},
                    },
                )
                logger.error("Error getting final response after max iterations: %s", e)

        await hooks.emit("execution:end", {})

    # ──────────────────────────────────────────────────────────────────────────
    # Steer queue drain
    # ──────────────────────────────────────────────────────────────────────────

    async def _drain_steer_queue(self, context, hooks: HookRegistry) -> None:
        """Inject any pending steer messages as user turns before the next LLM call."""
        if self._steer_queue is None:
            return
        while not self._steer_queue.empty():
            try:
                msg = self._steer_queue.get_nowait()
            except asyncio.QueueEmpty:
                break
            await context.add_message({"role": "user", "content": msg})
            await hooks.emit("steer:applied", {"message": msg})
            logger.info("[loop-vela] Steer injected: %.80r", msg)

    # ──────────────────────────────────────────────────────────────────────────
    # Streaming from provider — emits delta events per token
    # ──────────────────────────────────────────────────────────────────────────

    async def _stream_from_provider(
        self,
        provider,
        chat_request,
        context,
        tools,
        hooks,
        coordinator=None,
        provider_name=None,
        block_index: int = 0,
    ) -> AsyncIterator[str]:
        """Stream tokens from provider.  Emits content_block:start, :delta per token,
        and :end after the block completes.  tool_use blocks are accumulated from the
        stream and stored on self so _has_pending_tools / _process_tools can act on
        them after the generator finishes."""
        full_response = ""
        tools_list = list(tools.values()) if tools else []
        # State for tool_use accumulation — read by _has_pending_tools / _process_tools
        self._pending_streaming_tool_calls: list[dict] = []
        self._streaming_full_response = ""
        _current_tool: dict | None = None
        _current_thinking: dict | None = None
        _pending_thinking_blocks: list[dict] = []

        # Emit block start
        await hooks.emit(
            CONTENT_BLOCK_START,
            {"block_type": "text", "block_index": block_index},
        )

        try:
            stream_iter = provider.stream(chat_request, tools=tools_list)
        except LLMError as e:
            await hooks.emit(
                PROVIDER_ERROR,
                {
                    "provider": provider_name,
                    "error": {"type": type(e).__name__, "msg": str(e)},
                    "retryable": e.retryable,
                    "status_code": e.status_code,
                },
            )
            raise
        except Exception as e:
            await hooks.emit(
                PROVIDER_ERROR,
                {
                    "provider": provider_name,
                    "error": {"type": type(e).__name__, "msg": str(e)},
                },
            )
            raise

        async for chunk in stream_iter:
            if coordinator and coordinator.cancellation.is_immediate:
                if full_response:
                    await context.add_message(
                        {"role": "assistant", "content": full_response}
                    )
                return

            chunk_block_type = chunk.get("block_type")

            if chunk_block_type == "tool_use":
                # Finalize any in-progress thinking block before tool accumulation
                if _current_thinking is not None:
                    _pending_thinking_blocks.append(_current_thinking)
                    _current_thinking = None
                # Accumulate tool_use block data from the stream.
                # The first chunk for a new tool carries id + name; subsequent
                # chunks carry only partial JSON content.
                tool_id = chunk.get("tool_id") or chunk.get("id", "")
                tool_name = chunk.get("tool_name") or chunk.get("name", "")
                partial = chunk.get("content", "")

                if _current_tool is None or (
                    tool_id and _current_tool.get("id") != tool_id
                ):
                    _current_tool = {"id": tool_id, "name": tool_name, "input_json": ""}
                    self._pending_streaming_tool_calls.append(_current_tool)
                else:
                    # Back-fill name if it arrived on a later chunk
                    if tool_name and not _current_tool.get("name"):
                        _current_tool["name"] = tool_name
                _current_tool["input_json"] += partial
                continue

            # Accumulate thinking chunks; emitted as SSE events after stream completes
            if chunk_block_type == "thinking":
                if _current_thinking is None:
                    _current_thinking = {"thinking": "", "signature": None}
                _current_thinking["thinking"] += chunk.get("content", "") or chunk.get("thinking", "")
                sig = chunk.get("signature")
                if sig:
                    _current_thinking["signature"] = sig
                continue

            # Non-text, non-tool_use, non-thinking block — skip, reset trackers
            if chunk_block_type and chunk_block_type != "text":
                if _current_thinking is not None:
                    _pending_thinking_blocks.append(_current_thinking)
                    _current_thinking = None
                _current_tool = None
                continue

            # Text token — finalize any in-progress thinking block
            if _current_thinking is not None:
                _pending_thinking_blocks.append(_current_thinking)
                _current_thinking = None

            token = chunk.get("content", "")
            if token:
                # Emit per-token delta
                await hooks.emit(
                    CONTENT_BLOCK_DELTA,
                    {"token": token, "block_index": block_index},
                )
                yield token
                full_response += token

        # Finalize any remaining in-progress thinking block
        if _current_thinking is not None:
            _pending_thinking_blocks.append(_current_thinking)
            _current_thinking = None

        # Emit thinking block events before the text block end
        thinking_block_idx = block_index + 1
        for tb in _pending_thinking_blocks:
            await hooks.emit(
                CONTENT_BLOCK_START,
                {"block_type": "thinking", "block_index": thinking_block_idx},
            )
            await hooks.emit(
                CONTENT_BLOCK_END,
                {
                    "block_index": thinking_block_idx,
                    "block": {
                        "type": "thinking",
                        "thinking": tb["thinking"],
                        "signature": tb["signature"],
                    },
                },
            )
            thinking_block_idx += 1

        # Emit text block end
        await hooks.emit(
            CONTENT_BLOCK_END,
            {
                "block_index": block_index,
                "block": {"type": "text", "text": full_response},
            },
        )

        # Emit tool_use block events (start + end) after the text block
        tool_block_idx = thinking_block_idx
        for tb in self._pending_streaming_tool_calls:
            try:
                input_data = json.loads(tb["input_json"]) if tb["input_json"] else {}
            except json.JSONDecodeError:
                input_data = {}
            tb["input_parsed"] = input_data  # cache for _process_tools

            await hooks.emit(
                CONTENT_BLOCK_START,
                {"block_type": "tool_use", "block_index": tool_block_idx},
            )
            await hooks.emit(
                CONTENT_BLOCK_END,
                {
                    "block_index": tool_block_idx,
                    "block": {
                        "type": "tool_use",
                        "id": tb["id"],
                        "name": tb["name"],
                        "input": input_data,
                    },
                },
            )
            tool_block_idx += 1

        # Stash for _has_pending_tools / _process_tools
        self._streaming_full_response = full_response

        # Save assistant message to context only when there are no pending tool calls.
        # When tools ARE pending, _process_tools saves the complete message
        # (text + tool_calls list) atomically so the context is consistent.
        if not self._pending_streaming_tool_calls:
            if full_response:
                await context.add_message(
                    {"role": "assistant", "content": full_response}
                )

    def _extract_text_from_content(self, content) -> str:
        if isinstance(content, str):
            return content
        if not content:
            return ""
        text_parts = []
        for block in content:
            block_type = getattr(block, "type", None)
            type_value = (
                getattr(block_type, "value", block_type) if block_type else None
            )
            if type_value == "text" and hasattr(block, "text"):
                text_parts.append(block.text)
        return "\n\n".join(text_parts)

    # ──────────────────────────────────────────────────────────────────────────
    # Tool execution (copied from loop-streaming, unchanged)
    # ──────────────────────────────────────────────────────────────────────────

    async def _execute_tool(
        self,
        tool_call,
        tools: dict[str, Any],
        context,
        hooks: HookRegistry,
        coordinator: ModuleCoordinator | None = None,
    ) -> None:
        await self._execute_tool_with_result(
            tool_call, tools, context, hooks, coordinator
        )

    async def _execute_tool_only(
        self,
        tool_call,
        tools: dict[str, Any],
        hooks: HookRegistry,
        parallel_group_id: str,
        coordinator: ModuleCoordinator | None = None,
    ) -> tuple[str, str, str]:
        try:
            pre_result = await hooks.emit(
                TOOL_PRE,
                {
                    "tool_name": tool_call.name,
                    "tool_call_id": tool_call.id,
                    "tool_input": tool_call.arguments,
                    "parallel_group_id": parallel_group_id,
                },
            )
            if coordinator:
                pre_result = await coordinator.process_hook_result(
                    pre_result, "tool:pre", tool_call.name
                )
                if pre_result.action == "deny":
                    return (
                        tool_call.id,
                        tool_call.name,
                        f"Denied by hook: {pre_result.reason}",
                    )

            tool = tools.get(tool_call.name)
            if not tool:
                error_msg = f"Error: Tool '{tool_call.name}' not found"
                await hooks.emit(
                    TOOL_ERROR,
                    {
                        "tool_name": tool_call.name,
                        "tool_call_id": tool_call.id,
                        "error": {"type": "RuntimeError", "msg": error_msg},
                        "parallel_group_id": parallel_group_id,
                    },
                )
                return (tool_call.id, tool_call.name, error_msg)

            if coordinator:
                display_name = tool_call.name
                if tool_call.name == "delegate":
                    try:
                        _args = (
                            tool_call.arguments
                            if isinstance(tool_call.arguments, dict)
                            else json.loads(tool_call.arguments)
                        )
                        _agent = _args.get("agent", "")
                        if _agent:
                            display_name = _agent
                    except (json.JSONDecodeError, TypeError, AttributeError):
                        pass
                coordinator.cancellation.register_tool_start(tool_call.id, display_name)

            try:
                result = await tool.execute(tool_call.arguments)
            except Exception as e:
                result = ToolResult(success=False, error={"message": str(e)})
            finally:
                if coordinator:
                    coordinator.cancellation.register_tool_complete(tool_call.id)

            result_data = (
                result.model_dump() if hasattr(result, "model_dump") else str(result)
            )

            post_result = await hooks.emit(
                TOOL_POST,
                {
                    "tool_name": tool_call.name,
                    "tool_call_id": tool_call.id,
                    "tool_input": tool_call.arguments,
                    "result": result_data,
                    "parallel_group_id": parallel_group_id,
                },
            )
            if coordinator:
                await coordinator.process_hook_result(
                    post_result, "tool:post", tool_call.name
                )

            if (
                post_result.action == "inject_context"
                and post_result.ephemeral
                and post_result.context_injection
            ):
                self._pending_ephemeral_injections.append(
                    {
                        "role": post_result.context_injection_role,
                        "content": post_result.context_injection,
                        "append_to_last_tool_result": post_result.append_to_last_tool_result,
                    }
                )

            modified_result = None
            if post_result and post_result.data is not None:
                returned_result = post_result.data.get("result")
                if returned_result is not None and returned_result is not result_data:
                    modified_result = returned_result

            if modified_result is not None:
                if isinstance(modified_result, (dict, list)):
                    content = json.dumps(modified_result)
                else:
                    content = str(modified_result)
            else:
                content = result.get_serialized_output()

            # Emit tool:result so clients can show delegate output immediately
            await hooks.emit(
                "tool:result",
                {
                    "tool_call_id": tool_call.id,
                    "tool_name": tool_call.name,
                    "output": content,
                },
            )
            return (tool_call.id, tool_call.name, content)

        except Exception as e:
            logger.error("Tool %s failed: %s", tool_call.name, e)
            error_msg = f"Internal error executing tool: {str(e)}"
            await hooks.emit(
                TOOL_ERROR,
                {
                    "tool_name": tool_call.name,
                    "tool_call_id": tool_call.id,
                    "error": {"type": type(e).__name__, "msg": str(e)},
                    "parallel_group_id": parallel_group_id,
                },
            )
            return (tool_call.id, tool_call.name, error_msg)

    async def _execute_tool_with_result(
        self,
        tool_call,
        tools: dict[str, Any],
        context,
        hooks: HookRegistry,
        coordinator: ModuleCoordinator | None = None,
    ) -> dict:
        response_added = False
        try:
            pre_result = await hooks.emit(
                TOOL_PRE,
                {
                    "tool_name": tool_call.name,
                    "tool_call_id": tool_call.id,
                    "tool_input": tool_call.arguments,
                },
            )
            if coordinator:
                pre_result = await coordinator.process_hook_result(
                    pre_result, "tool:pre", tool_call.name
                )
                if pre_result.action == "deny":
                    await context.add_message(
                        {
                            "role": "tool",
                            "name": tool_call.name,
                            "tool_call_id": tool_call.id,
                            "content": f"Tool execution denied: {pre_result.reason}",
                        }
                    )
                    response_added = True
                    return {"success": False, "error": f"Denied: {pre_result.reason}"}

            tool = tools.get(tool_call.name)
            if not tool:
                await context.add_message(
                    {
                        "role": "tool",
                        "name": tool_call.name,
                        "tool_call_id": tool_call.id,
                        "content": f"Error: Tool '{tool_call.name}' not found",
                    }
                )
                response_added = True
                return {"success": False, "error": "Tool not found"}

            try:
                result = await tool.execute(tool_call.arguments)
            except Exception as e:
                result = ToolResult(success=False, error={"message": str(e)})

            result_data = (
                result.model_dump() if hasattr(result, "model_dump") else str(result)
            )

            post_result = await hooks.emit(
                TOOL_POST,
                {
                    "tool_name": tool_call.name,
                    "tool_call_id": tool_call.id,
                    "tool_input": tool_call.arguments,
                    "result": result_data,
                },
            )
            if coordinator:
                await coordinator.process_hook_result(
                    post_result, "tool:post", tool_call.name
                )

            if (
                post_result.action == "inject_context"
                and post_result.ephemeral
                and post_result.context_injection
            ):
                self._pending_ephemeral_injections.append(
                    {
                        "role": post_result.context_injection_role,
                        "content": post_result.context_injection,
                        "append_to_last_tool_result": post_result.append_to_last_tool_result,
                    }
                )

            modified_result = None
            if post_result and post_result.data is not None:
                returned_result = post_result.data.get("result")
                if returned_result is not None and returned_result is not result_data:
                    modified_result = returned_result

            if modified_result is not None:
                if isinstance(modified_result, (dict, list)):
                    tool_content = json.dumps(modified_result)
                else:
                    tool_content = str(modified_result)
            else:
                tool_content = result.get_serialized_output()

            await context.add_message(
                {
                    "role": "tool",
                    "name": tool_call.name,
                    "tool_call_id": tool_call.id,
                    "content": tool_content,
                }
            )
            response_added = True
            return {
                "success": result.success,
                "error": result.error if not result.success else None,
            }

        except Exception as e:
            logger.error(
                "Unexpected error executing tool %s: %s",
                tool_call.name,
                e,
                exc_info=True,
            )
            if not response_added:
                try:
                    await context.add_message(
                        {
                            "role": "tool",
                            "name": tool_call.name,
                            "tool_call_id": tool_call.id,
                            "content": f"Internal error executing tool: {str(e)}",
                        }
                    )
                except Exception as inner_e:
                    logger.error(
                        "Critical: Failed to add error response for tool_call_id %s: %s",
                        tool_call.id,
                        inner_e,
                    )
            return {"success": False, "error": str(e)}

    async def _has_pending_tools(self, context) -> bool:
        """True when _stream_from_provider accumulated tool_use blocks."""
        return bool(getattr(self, "_pending_streaming_tool_calls", None))

    async def _process_tools(self, context, tools, hooks) -> None:
        """Execute any tool_use blocks that were accumulated during streaming.

        Saves the complete assistant message (text + tool_calls) to context,
        runs all tool calls in parallel, then saves each tool result.
        """
        import types
        import uuid

        tool_calls_data = getattr(self, "_pending_streaming_tool_calls", [])
        full_response = getattr(self, "_streaming_full_response", "")

        if not tool_calls_data:
            return

        # Build lightweight tool-call objects compatible with _execute_tool_only
        tool_call_objs = [
            types.SimpleNamespace(
                id=tb["id"],
                name=tb["name"],
                arguments=tb.get("input_parsed", {}),
            )
            for tb in tool_calls_data
        ]

        # Save the complete assistant message (text + tool_calls list) atomically
        await context.add_message(
            {
                "role": "assistant",
                "content": full_response or "",
                "tool_calls": [
                    {"id": tc.id, "tool": tc.name, "arguments": tc.arguments}
                    for tc in tool_call_objs
                ],
            }
        )

        # Execute all tool calls in parallel
        parallel_group_id = str(uuid.uuid4())
        tool_tasks = [
            self._execute_tool_only(tc, tools, hooks, parallel_group_id, None)
            for tc in tool_call_objs
        ]
        tool_results = await asyncio.gather(*tool_tasks)

        # Save tool results to context
        for tool_call_id, tool_name, result_content in tool_results:
            await context.add_message(
                {
                    "role": "tool",
                    "name": tool_name,
                    "tool_call_id": tool_call_id,
                    "content": result_content,
                }
            )

        # Clear state so the next iteration starts clean
        self._pending_streaming_tool_calls = []
        self._streaming_full_response = ""

    def _select_provider(self, providers: dict[str, Any]) -> Any:
        if not providers:
            return None
        provider_list = []
        for name, provider in providers.items():
            priority = 100
            if hasattr(provider, "priority"):
                priority = provider.priority
            elif hasattr(provider, "config") and isinstance(provider.config, dict):
                priority = provider.config.get("priority", 100)
            provider_list.append((priority, name, provider))
        provider_list.sort(key=lambda x: x[0])
        if provider_list:
            return provider_list[0][2]
        return None
