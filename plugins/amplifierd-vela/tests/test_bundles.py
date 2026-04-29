import logging
import types

import pytest

from tests.conftest import FakeBundleRegistry
from vela_plugin import bundles
from vela_plugin.settings import Settings, VelaSettings


@pytest.fixture(autouse=True)
def reset_bundle_errors():
    bundles._bundle_errors.clear()
    yield
    bundles._bundle_errors.clear()


def test_activate_bundles_loads_each_one():
    state = types.SimpleNamespace(bundle_registry=FakeBundleRegistry())
    settings = Settings(bundles=["superpowers", "lifeos"], vela=VelaSettings())
    bundles.activate_bundles(state, settings)
    assert state.bundle_registry.loaded == ["superpowers", "lifeos"]
    assert bundles._bundle_errors == []


def test_activate_bundles_logs_each_success(caplog):
    state = types.SimpleNamespace(bundle_registry=FakeBundleRegistry())
    settings = Settings(bundles=["superpowers"], vela=VelaSettings())
    with caplog.at_level(logging.INFO, logger="vela_plugin.bundles"):
        bundles.activate_bundles(state, settings)
    assert any("[vela] activated bundle: superpowers" in r.message for r in caplog.records)


def test_activate_bundles_swallows_failure(caplog):
    state = types.SimpleNamespace(
        bundle_registry=FakeBundleRegistry(fail_for=["broken"])
    )
    settings = Settings(bundles=["superpowers", "broken"], vela=VelaSettings())
    with caplog.at_level(logging.WARNING, logger="vela_plugin.bundles"):
        bundles.activate_bundles(state, settings)  # must NOT raise
    assert "superpowers" in state.bundle_registry.loaded
    assert any("failed to activate bundle: broken" in r.message for r in caplog.records)
    assert any("broken" in err for err in bundles._bundle_errors)


def test_activate_bundles_no_registry_is_noop():
    state = types.SimpleNamespace(bundle_registry=None)
    settings = Settings(bundles=["superpowers"], vela=VelaSettings())
    bundles.activate_bundles(state, settings)  # must not raise
    assert bundles._bundle_errors == []


def test_activate_bundles_empty_list_is_noop():
    reg = FakeBundleRegistry()
    state = types.SimpleNamespace(bundle_registry=reg)
    settings = Settings(bundles=[], vela=VelaSettings())
    bundles.activate_bundles(state, settings)
    assert reg.loaded == []
