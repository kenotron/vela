"""LiveKit Agents worker entrypoint: joins a room, wires STT -> stock LLM ->
TTS with semantic turn detection (V2) and no preemptive generation (V3).

NOTE on this session's constraints: this entrypoint is structurally complete
and represents the intended production wiring, but was written in a
network-restricted, no-/dev/kvm sandbox with no live LiveKit server
reachable. It has NOT been exercised end-to-end against a real LiveKit
deployment in this session - see README.md ("What was and wasn't verified").
The pure-Python pieces it depends on (turn_detection.py, config.py) ARE unit
tested without any network or LiveKit server (see tests/).
"""

from __future__ import annotations

import logging

from livekit.agents import (
    AgentSession,
    JobContext,
    WorkerOptions,
    cli,
)
from livekit.plugins import openai
from livekit.plugins.turn_detector.multilingual import MultilingualModel

from voice_worker.config import load_config
from voice_worker.turn_detection import default_turn_detection_config

logger = logging.getLogger("vela-voice-worker")


async def entrypoint(ctx: JobContext) -> None:
    """Per-room agent entrypoint invoked by the LiveKit Agents worker runtime.

    Wires:
      - STT: stock STT plugin (see README for the exact plugin substituted in
        the sandbox config).
      - LLM: a stock LLM (V-series design doc requires no custom/fine-tuned
        model for this lane - "stock LLM" per the goal spec item 2).
      - TTS: stock TTS plugin.
      - Turn detection: LiveKit Agents' semantic MultilingualModel turn
        detector (V2) - never a raw silence-timer VAD. See
        turn_detection.default_turn_detection_config(), which raises if
        anything other than the semantic strategy is ever configured.
      - No preemptive generation (V3): AgentSession only triggers LLM
        generation after the turn detector confirms turn completion; this
        worker adds no additional speculative-generation logic on top of
        that default behavior.
    """
    turn_config = default_turn_detection_config()  # raises if misconfigured; see V2/V3
    worker_config = load_config()

    session = AgentSession(
        stt=openai.STT(),
        llm=openai.LLM(model=worker_config.stock_llm_model),
        tts=openai.TTS(),
        turn_detection=MultilingualModel(),
    )

    logger.info(
        "starting Vela voice session: turn_strategy=%s preemptive_generation=%s",
        turn_config.strategy.value,
        turn_config.allow_preemptive_generation,
    )

    await ctx.connect()
    await session.start(room=ctx.room)


def run_worker() -> None:
    """CLI entrypoint: `python -m voice_worker.agent` or the console script."""
    cli.run_app(WorkerOptions(entrypoint_fnc=entrypoint))


if __name__ == "__main__":
    run_worker()
