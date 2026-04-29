from pathlib import Path

from vela_plugin.settings import Settings, VelaSettings, load_settings


def test_load_full_settings(write_settings):
    path = write_settings({
        "bundles": ["superpowers", "lifeos"],
        "vela": {"auth_token": "abc123"},
    })
    s = load_settings(path)
    assert s.vela.auth_token == "abc123"
    assert s.bundles == ["superpowers", "lifeos"]


def test_load_missing_file_returns_defaults(tmp_path: Path):
    s = load_settings(tmp_path / "nonexistent.json")
    assert s == Settings(vela=VelaSettings(auth_token=""), bundles=[])


def test_load_missing_vela_key_returns_empty_token(write_settings):
    path = write_settings({"bundles": ["x"]})
    s = load_settings(path)
    assert s.vela.auth_token == ""
    assert s.bundles == ["x"]


def test_load_missing_bundles_returns_empty_list(write_settings):
    path = write_settings({"vela": {"auth_token": "t"}})
    s = load_settings(path)
    assert s.bundles == []
    assert s.vela.auth_token == "t"


def test_load_null_values_treated_as_empty(write_settings):
    path = write_settings({"vela": None, "bundles": None})
    s = load_settings(path)
    assert s.vela.auth_token == ""
    assert s.bundles == []
