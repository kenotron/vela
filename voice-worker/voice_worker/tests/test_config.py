from __future__ import annotations

from voice_worker.config import load_config


def test_load_config_falls_back_to_sandbox_stub_values(monkeypatch) -> None:
    """When no real LiveKit credentials are set in the environment, config
    falls back to clearly-fake sandbox stub values rather than raising - this
    is the explicit stub/sandbox substitution documented in README.md."""
    for key in ("LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET", "VELA_STOCK_LLM_MODEL"):
        monkeypatch.delenv(key, raising=False)

    config = load_config()

    assert config.livekit_url == "wss://sandbox.invalid.local"
    assert config.livekit_api_key == "sandbox-stub-key"
    assert config.livekit_api_secret == "sandbox-stub-secret"
    assert config.stock_llm_model == "gpt-4o-mini"


def test_load_config_honors_environment_overrides(monkeypatch) -> None:
    monkeypatch.setenv("LIVEKIT_URL", "wss://real.example.com")
    monkeypatch.setenv("LIVEKIT_API_KEY", "real-key")
    monkeypatch.setenv("LIVEKIT_API_SECRET", "real-secret")
    monkeypatch.setenv("VELA_STOCK_LLM_MODEL", "gpt-4o")

    config = load_config()

    assert config.livekit_url == "wss://real.example.com"
    assert config.livekit_api_key == "real-key"
    assert config.livekit_api_secret == "real-secret"
    assert config.stock_llm_model == "gpt-4o"
