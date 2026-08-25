"""Durable local broker store (design doc 4.1's `job_id -> (machine_id, spec,
last_known_state)` cache; README.md residual "A local durable broker store").

Mirrors `services/ledger/ledger_service/db.py`'s durability posture exactly:
WAL journal mode + `synchronous=FULL` on a file-backed SQLite connection.
WAL alone only changes *how* writes are logged; FULL is what fsyncs the WAL
on every commit and is what actually protects against a `kill -9` mid-write
losing worker/job bindings.

This store persists the two pieces of broker state that must survive a
restart:

- worker registry rows (machine_id, labels, runtimes, last_heartbeat_ms,
  active_jobs, connected) -- so a restarted broker knows who it last saw,
  and reconciliation (reconciliation.py) has something to reconcile
  against once a worker reconnects.
- job -> machine bindings (sessions.py's `SessionTable`) -- so decision
  relay and reconciliation both still know which worker owns which job
  after a restart.

Not persisted: the live WebSocket sender objects (`SessionTable._senders`)
-- those are inherently process-local and are rebuilt from scratch as
workers reconnect after a restart. `connected` is set to `False` for every
row on load, since no session can possibly still be open across a process
restart.
"""

from __future__ import annotations

import sqlite3
import threading
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS workers (
    machine_id       TEXT PRIMARY KEY,
    labels_json       TEXT NOT NULL,
    runtimes_json     TEXT NOT NULL,
    last_heartbeat_ms INTEGER NOT NULL,
    active_jobs       INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS job_bindings (
    job_id     TEXT PRIMARY KEY,
    machine_id TEXT NOT NULL
);
"""


@dataclass
class BrokerStore:
    """Thread-safe SQLite-backed durable store for registry + job bindings.

    A file path (never ``:memory:`` in production use) is what makes this
    durable across process restarts -- same posture as
    `services/ledger`'s `LedgerDB`.
    """

    path: Path
    _local: threading.local = field(default_factory=threading.local, repr=False)
    _write_lock: threading.Lock = field(default_factory=threading.Lock, repr=False)

    def __post_init__(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        with self._connect() as conn:
            conn.executescript(SCHEMA)
            conn.commit()

    def _connect(self) -> sqlite3.Connection:
        if not hasattr(self._local, "conn"):
            conn = sqlite3.connect(str(self.path), check_same_thread=False)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA journal_mode=WAL")
            # FULL: fsync on every commit -- the durability guarantee, same
            # rationale as ledger_service/db.py. WAL mode alone does not
            # imply fsync-on-commit.
            conn.execute("PRAGMA synchronous=FULL")
            self._local.conn = conn
        return self._local.conn

    @contextmanager
    def _cursor(self) -> Iterator[sqlite3.Cursor]:
        conn = self._connect()
        cur = conn.cursor()
        try:
            yield cur
            conn.commit()
        except Exception:
            conn.rollback()
            raise

    def close(self) -> None:
        if hasattr(self._local, "conn"):
            self._local.conn.close()
            del self._local.conn

    # -- workers --------------------------------------------------------

    def upsert_worker(
        self,
        *,
        machine_id: str,
        labels: list[str],
        runtimes: list[str],
        last_heartbeat_ms: int,
        active_jobs: int,
    ) -> None:
        import json

        with self._write_lock, self._cursor() as cur:
            cur.execute(
                """
                INSERT INTO workers (
                    machine_id, labels_json, runtimes_json,
                    last_heartbeat_ms, active_jobs
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(machine_id) DO UPDATE SET
                    labels_json = excluded.labels_json,
                    runtimes_json = excluded.runtimes_json,
                    last_heartbeat_ms = excluded.last_heartbeat_ms,
                    active_jobs = excluded.active_jobs
                """,
                (
                    machine_id,
                    json.dumps(sorted(labels)),
                    json.dumps(runtimes),
                    last_heartbeat_ms,
                    active_jobs,
                ),
            )

    def load_workers(self) -> list[dict]:
        import json

        with self._cursor() as cur:
            cur.execute("SELECT * FROM workers")
            rows = cur.fetchall()
        return [
            {
                "machine_id": r["machine_id"],
                "labels": json.loads(r["labels_json"]),
                "runtimes": json.loads(r["runtimes_json"]),
                "last_heartbeat_ms": r["last_heartbeat_ms"],
                "active_jobs": r["active_jobs"],
            }
            for r in rows
        ]

    # -- job bindings -----------------------------------------------------

    def bind_job(self, job_id: str, machine_id: str) -> None:
        with self._write_lock, self._cursor() as cur:
            cur.execute(
                """
                INSERT INTO job_bindings (job_id, machine_id) VALUES (?, ?)
                ON CONFLICT(job_id) DO UPDATE SET machine_id = excluded.machine_id
                """,
                (job_id, machine_id),
            )

    def unbind_job(self, job_id: str) -> None:
        with self._write_lock, self._cursor() as cur:
            cur.execute("DELETE FROM job_bindings WHERE job_id = ?", (job_id,))

    def load_job_bindings(self) -> dict[str, str]:
        with self._cursor() as cur:
            cur.execute("SELECT job_id, machine_id FROM job_bindings")
            rows = cur.fetchall()
        return {r["job_id"]: r["machine_id"] for r in rows}
