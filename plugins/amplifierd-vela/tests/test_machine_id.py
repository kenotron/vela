"""Tests for vela_plugin.machine_id – platform-stable machine identity."""

from __future__ import annotations

import re
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

# UUID regex: 8-4-4-4-12 hex digits
UUID_RE = re.compile(
    r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
)


def is_uuid(s: str) -> bool:
    return bool(UUID_RE.match(s))


# ---------------------------------------------------------------------------
# Helpers to make each "platform source" unavailable so we can isolate them
# ---------------------------------------------------------------------------

DARWIN_IOREG_OUTPUT = """+-o Root  <class IORegistryEntry, id 0x100000100, retain 13>
  "IOPlatformUUID" = "65E872B0-3343-5255-8409-8C2C13974937"
"""


@pytest.fixture()
def no_persist(tmp_path, monkeypatch):
    """Redirect AMPLIFIER_HOME to a fresh temp dir (no pre-existing machine-id)."""
    monkeypatch.setenv("AMPLIFIER_HOME", str(tmp_path / "amplifier"))
    return tmp_path / "amplifier" / "machine-id"


# ---------------------------------------------------------------------------
# macOS path
# ---------------------------------------------------------------------------


def test_macos_returns_ioreg_uuid(monkeypatch, no_persist):
    """On macOS, get_machine_id() should parse the IOPlatformUUID from ioreg."""
    import vela_plugin.machine_id as mid

    monkeypatch.setattr("sys.platform", "darwin")

    fake_result = MagicMock()
    fake_result.stdout = DARWIN_IOREG_OUTPUT

    with patch("subprocess.run", return_value=fake_result) as mock_run:
        result = mid.get_machine_id()

    mock_run.assert_called_once_with(
        ["/usr/sbin/ioreg", "-rd1", "-c", "IOPlatformExpertDevice"],
        capture_output=True,
        text=True,
    )
    assert result == "65E872B0-3343-5255-8409-8C2C13974937"
    assert is_uuid(result)


def test_macos_falls_through_on_empty_ioreg(monkeypatch, no_persist, tmp_path):
    """If ioreg returns no IOPlatformUUID line, fall through to next source."""
    import vela_plugin.machine_id as mid

    monkeypatch.setattr("sys.platform", "darwin")

    # Create a /etc/machine-id substitute to catch the fallthrough
    machine_id_file = tmp_path / "machine-id"
    machine_id_file.write_text("aaaabbbb-cccc-dddd-eeee-ffffffffffff\n")

    fake_result = MagicMock()
    fake_result.stdout = "no uuid here\n"

    with patch("subprocess.run", return_value=fake_result):
        with patch("pathlib.Path.exists") as mock_exists:
            # First call (dmi) → False, second call (machine-id) depends on path
            # Instead just let it fall all the way to generated UUID
            mock_exists.return_value = False
            result = mid.get_machine_id()

    assert is_uuid(result)


# ---------------------------------------------------------------------------
# Linux DMI path
# ---------------------------------------------------------------------------


def test_linux_dmi_uuid(monkeypatch, no_persist, tmp_path):
    """On Linux, read /sys/class/dmi/id/product_uuid when it exists."""
    import vela_plugin.machine_id as mid

    monkeypatch.setattr("sys.platform", "linux")

    dmi_file = tmp_path / "product_uuid"
    dmi_file.write_text("AABBCCDD-1122-3344-5566-778899AABBCC\n")

    with patch("pathlib.Path.exists") as mock_exists:
        with patch("pathlib.Path.read_text") as mock_read:
            mock_exists.return_value = True
            mock_read.return_value = "AABBCCDD-1122-3344-5566-778899AABBCC\n"
            result = mid.get_machine_id()

    assert result == "AABBCCDD-1122-3344-5566-778899AABBCC"
    assert is_uuid(result)


def test_linux_dmi_permission_error_falls_through(monkeypatch, no_persist, tmp_path):
    """If /sys/class/dmi/id/product_uuid raises PermissionError, fall through."""
    import vela_plugin.machine_id as mid

    monkeypatch.setattr("sys.platform", "linux")

    # We need a real /etc/machine-id substitute
    etc_mid = tmp_path / "etc_machine_id"
    etc_mid.write_text("ccccdddd-1234-5678-9abc-def012345678\n")

    def fake_exists(self):
        # dmi path exists (to trigger the read), etc/machine-id does not
        if "product_uuid" in str(self):
            return True
        if "machine-id" in str(self) and "amplifier" not in str(self):
            return True
        return False

    def fake_read_text(self):
        if "product_uuid" in str(self):
            raise PermissionError("no root")
        if "machine-id" in str(self) and "amplifier" not in str(self):
            return "ccccdddd-1234-5678-9abc-def012345678\n"
        raise FileNotFoundError

    with patch.object(Path, "exists", fake_exists):
        with patch.object(Path, "read_text", fake_read_text):
            result = mid.get_machine_id()

    assert result == "ccccdddd-1234-5678-9abc-def012345678"
    assert is_uuid(result)


# ---------------------------------------------------------------------------
# /etc/machine-id path
# ---------------------------------------------------------------------------


def test_etc_machine_id(monkeypatch, no_persist, tmp_path):
    """Fall through to /etc/machine-id when DMI is absent."""
    import vela_plugin.machine_id as mid

    monkeypatch.setattr("sys.platform", "linux")

    def fake_exists(self):
        if "product_uuid" in str(self):
            return False  # DMI not present
        if str(self) == "/etc/machine-id":
            return True
        return False

    def fake_read_text(self):
        if str(self) == "/etc/machine-id":
            return "deadbeef-dead-beef-dead-beefdeadbeef\n"
        raise FileNotFoundError

    with patch.object(Path, "exists", fake_exists):
        with patch.object(Path, "read_text", fake_read_text):
            result = mid.get_machine_id()

    assert result == "deadbeef-dead-beef-dead-beefdeadbeef"
    assert is_uuid(result)


# ---------------------------------------------------------------------------
# Persisted ~/.amplifier/machine-id path
# ---------------------------------------------------------------------------


def test_persisted_machine_id_returned_if_exists(monkeypatch, tmp_path):
    """If ~/.amplifier/machine-id already exists, return its content."""
    import vela_plugin.machine_id as mid

    persist_dir = tmp_path / "amplifier"
    persist_dir.mkdir()
    persist_file = persist_dir / "machine-id"
    persist_file.write_text("11112222-3333-4444-5555-666677778888")

    monkeypatch.setenv("AMPLIFIER_HOME", str(persist_dir))
    monkeypatch.setattr("sys.platform", "linux")

    # Make all platform-specific paths appear absent
    def fake_exists(self):
        if "product_uuid" in str(self) or str(self) == "/etc/machine-id":
            return False
        # Let the persisted file check succeed
        return self == persist_file

    def fake_read_text(self):
        if self == persist_file:
            return "11112222-3333-4444-5555-666677778888"
        raise FileNotFoundError

    with patch.object(Path, "exists", fake_exists):
        with patch.object(Path, "read_text", fake_read_text):
            result = mid.get_machine_id()

    assert result == "11112222-3333-4444-5555-666677778888"
    assert is_uuid(result)


# ---------------------------------------------------------------------------
# UUID generation & persistence fallback
# ---------------------------------------------------------------------------


def test_generates_and_persists_uuid_when_no_source_available(monkeypatch, tmp_path):
    """When no platform source or persisted file exists, generate a new UUID."""
    import vela_plugin.machine_id as mid

    persist_dir = tmp_path / "amplifier_home"
    monkeypatch.setenv("AMPLIFIER_HOME", str(persist_dir))
    monkeypatch.setattr("sys.platform", "linux")

    # Make all paths appear absent
    with patch.object(Path, "exists", return_value=False):
        result = mid.get_machine_id()

    assert is_uuid(result)
    # Check it was persisted
    persist_file = persist_dir / "machine-id"
    assert persist_file.exists()
    assert persist_file.read_text() == result


def test_generated_uuid_is_stable_across_calls(monkeypatch, tmp_path):
    """Second call should return the same UUID from the persisted file."""
    import vela_plugin.machine_id as mid

    persist_dir = tmp_path / "amplifier_stable"
    monkeypatch.setenv("AMPLIFIER_HOME", str(persist_dir))
    monkeypatch.setattr("sys.platform", "linux")

    with patch.object(Path, "exists", return_value=False):
        first = mid.get_machine_id()

    # Second call: persist file now exists, all platform paths absent except persist
    second = mid.get_machine_id()
    assert first == second


def test_return_value_is_string(monkeypatch, tmp_path):
    """get_machine_id() must return a str, not bytes."""
    import vela_plugin.machine_id as mid

    monkeypatch.setenv("AMPLIFIER_HOME", str(tmp_path / "amplifier"))
    monkeypatch.setattr("sys.platform", "linux")

    with patch.object(Path, "exists", return_value=False):
        result = mid.get_machine_id()

    assert isinstance(result, str)
