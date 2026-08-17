"""Turn-detection configuration for the Vela voice worker.

V2 requires semantic turn detection (LiveKit Agents' built-in turn detector
model), never a raw silence-timer VAD. This module centralizes that choice as
a small, pure-Python config object so:

  - It is trivially unit-testable without a live LiveKit server or network
    (see tests/test_turn_detection.py).
  - There is exactly one place in this worker where the turn-detection
    strategy is chosen, making it easy to audit that a silence-timer VAD
    was never (re)introduced as a shortcut.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum


class TurnDetectionStrategy(str, Enum):
    """The set of turn-detection strategies this worker knows how to wire up.

    SEMANTIC is the only strategy permitted by V2. SILENCE_TIMER exists in
    this enum purely so tests can assert it is never the configured/active
    strategy - it must never be constructed by production wiring.
    """

    SEMANTIC = "semantic"
    SILENCE_TIMER = "silence_timer"


@dataclass(frozen=True)
class TurnDetectionConfig:
    """Configuration for how the agent session decides a user has finished
    their turn.

    Attributes:
        strategy: which detection strategy to use. Must be SEMANTIC per V2.
        allow_preemptive_generation: must be False per V3 - the agent may not
            begin generating a response before the turn-detector confirms the
            user's turn is complete. This flag exists (rather than simply
            omitting the capability) so that V3 compliance is directly
            assertable in a unit test rather than only true by omission.
    """

    strategy: TurnDetectionStrategy
    allow_preemptive_generation: bool = False

    def __post_init__(self) -> None:
        if self.strategy is not TurnDetectionStrategy.SEMANTIC:
            raise ValueError(
                "Vela voice-worker requires semantic turn detection (V2); "
                f"got unsupported strategy: {self.strategy!r}"
            )
        if self.allow_preemptive_generation:
            raise ValueError(
                "Preemptive/speculative generation before turn completion is "
                "disallowed by V3; allow_preemptive_generation must be False."
            )


def default_turn_detection_config() -> TurnDetectionConfig:
    """The single production configuration used by the agent entrypoint.

    Always semantic detection, never preemptive generation. Centralizing this
    as one function makes it easy to grep for and reason about.
    """
    return TurnDetectionConfig(strategy=TurnDetectionStrategy.SEMANTIC)
