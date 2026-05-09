"""Tests for vela_plugin.mdns_service – mDNS advertising for amplifierd."""

from __future__ import annotations

import socket
from unittest.mock import MagicMock, patch

import pytest


# ---------------------------------------------------------------------------
# Import check
# ---------------------------------------------------------------------------


def test_module_imports():
    """Module imports without ImportError."""
    import vela_plugin.mdns_service as mod  # noqa: F401


def test_class_exists():
    """AmplifierdMdnsService class is present in module."""
    from vela_plugin.mdns_service import AmplifierdMdnsService  # noqa: F401


def test_helper_function_exists():
    """_get_local_ip helper is present in module."""
    from vela_plugin.mdns_service import _get_local_ip  # noqa: F401


# ---------------------------------------------------------------------------
# Constructor
# ---------------------------------------------------------------------------


def test_init_stores_machine_id_and_port():
    """Constructor stores machine_id and port attributes."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="test-uuid", port=9000)
    assert svc.machine_id == "test-uuid"
    assert svc.port == 9000


def test_init_default_port():
    """Default port is 8410."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="abc")
    assert svc.port == 8410


def test_init_zeroconf_and_info_none():
    """_zeroconf and _info are None after construction."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="abc")
    assert svc._zeroconf is None
    assert svc._info is None


# ---------------------------------------------------------------------------
# _get_local_ip helper
# ---------------------------------------------------------------------------


def test_get_local_ip_returns_string():
    """_get_local_ip returns a string."""
    from vela_plugin.mdns_service import _get_local_ip

    result = _get_local_ip()
    assert isinstance(result, str)


def test_get_local_ip_fallback_on_error():
    """_get_local_ip returns '127.0.0.1' when network is unavailable."""
    from vela_plugin.mdns_service import _get_local_ip

    with patch("socket.socket") as mock_socket_cls:
        mock_sock = MagicMock()
        mock_socket_cls.return_value.__enter__ = MagicMock(return_value=mock_sock)
        mock_socket_cls.return_value.__exit__ = MagicMock(return_value=False)
        mock_sock.connect.side_effect = OSError("no network")
        result = _get_local_ip()

    assert result == "127.0.0.1"


# ---------------------------------------------------------------------------
# start() method
# ---------------------------------------------------------------------------


@pytest.fixture
def mock_zeroconf_module():
    """Provide mock ServiceInfo and Zeroconf."""
    mock_service_info = MagicMock()
    mock_zeroconf_instance = MagicMock()
    mock_zeroconf_cls = MagicMock(return_value=mock_zeroconf_instance)
    mock_service_info_cls = MagicMock(return_value=mock_service_info)

    with patch.dict(
        "sys.modules",
        {
            "zeroconf": MagicMock(
                ServiceInfo=mock_service_info_cls,
                Zeroconf=mock_zeroconf_cls,
            )
        },
    ):
        yield {
            "ServiceInfo": mock_service_info_cls,
            "Zeroconf": mock_zeroconf_cls,
            "zeroconf_instance": mock_zeroconf_instance,
            "service_info": mock_service_info,
        }


def test_start_registers_service(mock_zeroconf_module):
    """start() registers the mDNS service via zeroconf."""
    from importlib import reload

    import vela_plugin.mdns_service as mod

    reload(mod)  # re-evaluate with patched sys.modules if needed

    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="my-uuid", port=8410)

    with (
        patch("socket.gethostname", return_value="testhost"),
        patch("socket.gethostbyname", return_value="192.168.1.100"),
    ):
        svc.start(label="amplifierd")

    # Zeroconf was instantiated
    mock_zeroconf_module["Zeroconf"].assert_called_once()
    # register_service was called
    mock_zeroconf_module["zeroconf_instance"].register_service.assert_called_once()


def test_start_sets_zeroconf_and_info():
    """start() sets _zeroconf and _info on the instance."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="my-uuid", port=8410)

    mock_zc_instance = MagicMock()
    mock_info = MagicMock()

    mock_zc_cls = MagicMock(return_value=mock_zc_instance)
    mock_info_cls = MagicMock(return_value=mock_info)

    with (
        patch("socket.gethostname", return_value="testhost"),
        patch("socket.gethostbyname", return_value="192.168.1.100"),
        patch("zeroconf.Zeroconf", mock_zc_cls),
        patch("zeroconf.ServiceInfo", mock_info_cls),
    ):
        svc.start(label="amplifierd")

    assert svc._zeroconf is mock_zc_instance
    assert svc._info is mock_info


def test_start_uses_get_local_ip_on_gaierror():
    """start() falls back to _get_local_ip() when gethostbyname raises gaierror."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="my-uuid")

    mock_zc_instance = MagicMock()
    mock_info = MagicMock()

    with (
        patch("socket.gethostname", return_value="testhost"),
        patch(
            "socket.gethostbyname",
            side_effect=socket.gaierror("resolution failed"),
        ),
        patch(
            "vela_plugin.mdns_service._get_local_ip", return_value="10.0.0.1"
        ) as mock_get_ip,
        patch("zeroconf.Zeroconf", MagicMock(return_value=mock_zc_instance)),
        patch("zeroconf.ServiceInfo", MagicMock(return_value=mock_info)),
    ):
        svc.start()

    mock_get_ip.assert_called_once()


def test_start_logs_warning_on_failure(caplog):
    """start() logs a warning when service registration fails."""
    import logging

    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="my-uuid")

    with (
        patch("socket.gethostname", return_value="testhost"),
        patch("socket.gethostbyname", return_value="127.0.0.1"),
        patch("zeroconf.Zeroconf", side_effect=RuntimeError("zeroconf broken")),
        caplog.at_level(logging.WARNING, logger="vela_plugin.mdns_service"),
    ):
        svc.start()  # must not raise

    assert any("warning" in r.levelname.lower() for r in caplog.records)


# ---------------------------------------------------------------------------
# stop() method
# ---------------------------------------------------------------------------


def test_stop_unregisters_and_closes():
    """stop() unregisters service and closes zeroconf."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="my-uuid")
    mock_zc = MagicMock()
    mock_info = MagicMock()
    svc._zeroconf = mock_zc
    svc._info = mock_info

    svc.stop()

    mock_zc.unregister_service.assert_called_once_with(mock_info)
    mock_zc.close.assert_called_once()


def test_stop_sets_zeroconf_and_info_to_none():
    """stop() sets _zeroconf and _info to None in finally block."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="my-uuid")
    svc._zeroconf = MagicMock()
    svc._info = MagicMock()

    svc.stop()

    assert svc._zeroconf is None
    assert svc._info is None


def test_stop_noop_when_not_started():
    """stop() does nothing if _zeroconf is None (never started)."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="my-uuid")
    svc.stop()  # must not raise

    assert svc._zeroconf is None
    assert svc._info is None


def test_stop_logs_warning_on_failure(caplog):
    """stop() logs a warning when unregistration fails."""
    import logging

    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="my-uuid")
    mock_zc = MagicMock()
    mock_zc.unregister_service.side_effect = RuntimeError("failed")
    svc._zeroconf = mock_zc
    svc._info = MagicMock()

    with caplog.at_level(logging.WARNING, logger="vela_plugin.mdns_service"):
        svc.stop()  # must not raise

    assert any("warning" in r.levelname.lower() for r in caplog.records)
    # finally must still clear both to None
    assert svc._zeroconf is None
    assert svc._info is None


# ---------------------------------------------------------------------------
# Service info fields
# ---------------------------------------------------------------------------


def test_start_service_type_and_name():
    """ServiceInfo is created with correct type and name."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="abc-123", port=8410)

    mock_zc = MagicMock()
    captured_args: list = []

    def capture_service_info(*args, **kwargs):
        captured_args.extend([args, kwargs])
        return MagicMock()

    with (
        patch("socket.gethostname", return_value="host"),
        patch("socket.gethostbyname", return_value="1.2.3.4"),
        patch("zeroconf.Zeroconf", MagicMock(return_value=mock_zc)),
        patch("zeroconf.ServiceInfo", side_effect=capture_service_info),
    ):
        svc.start(label="mynode")

    # First positional arg: service type
    assert captured_args[0][0] == "_amplifierd._tcp.local."
    # Second positional arg: service name
    assert captured_args[0][1] == "mynode._amplifierd._tcp.local."


def test_start_service_properties():
    """ServiceInfo properties contain machine_id and version."""
    from vela_plugin.mdns_service import AmplifierdMdnsService

    svc = AmplifierdMdnsService(machine_id="abc-123", port=8410)

    mock_zc = MagicMock()
    captured_kwargs: dict = {}

    def capture_service_info(*args, **kwargs):
        captured_kwargs.update(kwargs)
        return MagicMock()

    with (
        patch("socket.gethostname", return_value="host"),
        patch("socket.gethostbyname", return_value="1.2.3.4"),
        patch("zeroconf.Zeroconf", MagicMock(return_value=mock_zc)),
        patch("zeroconf.ServiceInfo", side_effect=capture_service_info),
    ):
        svc.start(label="mynode")

    props = captured_kwargs["properties"]
    assert props["machine_id"] == "abc-123"
    assert props["version"] == "0.1.0"
