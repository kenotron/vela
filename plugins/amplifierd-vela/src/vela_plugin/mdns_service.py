"""mDNS service advertising for amplifierd.

Registers ``_amplifierd._tcp.local.`` so Android devices on the same
network can discover this node without a known IP address.  The service
record carries a machine_id TXT property that lets peers correlate mDNS
announcements with amplifierd API responses.
"""

from __future__ import annotations

import logging
import socket

logger = logging.getLogger(__name__)


def _get_local_ip() -> str:
    """Return the best-guess local IPv4 address (non-loopback).

    Opens a UDP socket toward 8.8.8.8 (no data is actually sent) and reads
    back the source address the OS assigned.  Falls back to ``'127.0.0.1'``
    if the OS cannot determine a suitable address.
    """
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        return "127.0.0.1"


class AmplifierdMdnsService:
    """Advertises amplifierd over mDNS / DNS-SD.

    Registers a ``_amplifierd._tcp.local.`` service record so that Android
    clients on the same LAN can discover this node without needing a
    hard-coded IP address.
    """

    def __init__(self, machine_id: str, port: int = 8410) -> None:
        self.machine_id = machine_id
        self.port = port
        self._zeroconf = None
        self._info = None

    def start(self, label: str = "amplifierd") -> None:
        """Register the mDNS service.

        Args:
            label: The instance name prefix, yielding
                   ``<label>._amplifierd._tcp.local.``
        """
        try:
            from zeroconf import ServiceInfo, Zeroconf  # noqa: PLC0415

            hostname = socket.gethostname()
            try:
                local_ip = socket.gethostbyname(hostname)
            except socket.gaierror:
                local_ip = _get_local_ip()

            self._zeroconf = Zeroconf()
            self._info = ServiceInfo(
                "_amplifierd._tcp.local.",
                f"{label}._amplifierd._tcp.local.",
                addresses=[socket.inet_aton(local_ip)],
                port=self.port,
                properties={"machine_id": self.machine_id, "version": "0.1.0"},
            )
            self._zeroconf.register_service(self._info)
            logger.info(
                "mDNS: registered %s._amplifierd._tcp.local. at %s:%s (machine_id=%s)",
                label,
                local_ip,
                self.port,
                self.machine_id,
            )
        except Exception:
            logger.warning("mDNS: failed to register service", exc_info=True)

    def stop(self) -> None:
        """Deregister the mDNS service and close the Zeroconf socket."""
        try:
            if self._zeroconf and self._info:
                self._zeroconf.unregister_service(self._info)
                self._zeroconf.close()
                logger.info("mDNS: service deregistered")
        except Exception:
            logger.warning("mDNS: failed to deregister service", exc_info=True)
        finally:
            self._zeroconf = None
            self._info = None
