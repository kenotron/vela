from __future__ import annotations

import pytest

from voice_worker.turn_detection import (
    TurnDetectionConfig,
    TurnDetectionStrategy,
    default_turn_detection_config,
)


def test_default_config_uses_semantic_strategy() -> None:
    """V2: semantic turn detection, never a raw silence-timer VAD."""
    config = default_turn_detection_config()
    assert config.strategy is TurnDetectionStrategy.SEMANTIC


def test_default_config_disallows_preemptive_generation() -> None:
    """V3: no preemptive/speculative generation before turn completion."""
    config = default_turn_detection_config()
    assert config.allow_preemptive_generation is False


def test_silence_timer_strategy_is_rejected() -> None:
    """Constructing a config with SILENCE_TIMER must fail - this is the
    concrete guardrail that prevents someone from swapping in a raw
    silence-timer VAD as a shortcut."""
    with pytest.raises(ValueError, match="semantic turn detection"):
        TurnDetectionConfig(strategy=TurnDetectionStrategy.SILENCE_TIMER)


def test_preemptive_generation_flag_is_rejected_even_with_semantic_strategy() -> None:
    """Even if the strategy is correctly SEMANTIC, allow_preemptive_generation
    must not be settable to True - V3 is a separate, independently-enforced
    constraint from V2."""
    with pytest.raises(ValueError, match="Preemptive"):
        TurnDetectionConfig(
            strategy=TurnDetectionStrategy.SEMANTIC,
            allow_preemptive_generation=True,
        )


def test_valid_semantic_non_preemptive_config_constructs_cleanly() -> None:
    config = TurnDetectionConfig(
        strategy=TurnDetectionStrategy.SEMANTIC,
        allow_preemptive_generation=False,
    )
    assert config.strategy is TurnDetectionStrategy.SEMANTIC
    assert config.allow_preemptive_generation is False
