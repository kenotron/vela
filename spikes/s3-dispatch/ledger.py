"""Minimal standalone in-memory ledger stub for spike S-3.

Mirrors android/host-tools/src/main/java/com/vela/hosttools/InMemoryLedgerRepository.kt
(read-only reference; lane 2.1's real ledger service was not merged at spike
time -- see goal file). This lives entirely inside spikes/s3-dispatch/ and is
NOT a fork or modification of any other lane's code.
"""

import threading
import time
import uuid


class InMemoryLedger:
    """Thread-safe in-memory ledger. Lives OUTSIDE the chat transcript --
    this is the crux of the spike: does the ledger record survive
    amplifier-agent's transcript reconciliation of a subsequent turn?
    """

    def __init__(self):
        self._lock = threading.Lock()
        self._entries: dict[str, dict] = {}

    def append(self, title: str, summary: str) -> str:
        job_id = str(uuid.uuid4())
        with self._lock:
            self._entries[job_id] = {
                "id": job_id,
                "title": title,
                "summary": summary,
                "created_at_epoch_ms": int(time.time() * 1000),
                "source": "dispatch_to_fleet",
                "status": "PENDING",
            }
        return job_id

    def get(self, job_id: str) -> dict | None:
        with self._lock:
            return self._entries.get(job_id)

    def size(self) -> int:
        with self._lock:
            return len(self._entries)
