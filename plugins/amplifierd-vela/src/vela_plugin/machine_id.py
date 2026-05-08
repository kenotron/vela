"""Platform-stable machine identity with a priority fallback chain.

Priority order:
  macOS  → IOPlatformUUID via ``ioreg`` (hardware-bound, survives reboots)
  Linux  → /sys/class/dmi/id/product_uuid (firmware-bound, may need root)
         → /etc/machine-id (distro-assigned, persists across reboots)
  Any    → ~/.amplifier/machine-id (generated once, persisted)

The last entry acts as the universal fallback: if no platform source is
available (or readable) a random UUID is generated, written to disk, and
returned on every subsequent call.
"""

from __future__ import annotations

import os
import pathlib
import subprocess
import sys
import uuid


def get_machine_id() -> str:
    """Return a stable machine UUID, generating and persisting one if needed."""

    # ------------------------------------------------------------------
    # 1. macOS – IOPlatformUUID from ioreg
    # ------------------------------------------------------------------
    if sys.platform == "darwin":
        result = subprocess.run(
            ["ioreg", "-rd1", "-c", "IOPlatformExpertDevice"],
            capture_output=True,
            text=True,
        )
        for line in result.stdout.splitlines():
            if "IOPlatformUUID" in line:
                return line.split('"')[-2]

    # ------------------------------------------------------------------
    # 2. Linux – /sys/class/dmi/id/product_uuid (requires root on some distros)
    # ------------------------------------------------------------------
    dmi = pathlib.Path("/sys/class/dmi/id/product_uuid")
    if dmi.exists():
        try:
            return dmi.read_text().strip()
        except PermissionError:
            pass  # fall through to next source

    # ------------------------------------------------------------------
    # 3. Linux – /etc/machine-id (always readable, distro-assigned)
    # ------------------------------------------------------------------
    machine_id = pathlib.Path("/etc/machine-id")
    if machine_id.exists():
        return machine_id.read_text().strip()

    # ------------------------------------------------------------------
    # 4. Universal fallback – ~/.amplifier/machine-id (generated once)
    # ------------------------------------------------------------------
    amplifier_home = pathlib.Path(
        os.environ.get("AMPLIFIER_HOME", str(pathlib.Path.home() / ".amplifier"))
    )
    persist = amplifier_home / "machine-id"
    if persist.exists():
        return persist.read_text().strip()

    new_id = str(uuid.uuid4())
    persist.parent.mkdir(parents=True, exist_ok=True)
    persist.write_text(new_id)
    return new_id
