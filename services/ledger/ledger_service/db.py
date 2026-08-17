"""Durable SQLite storage for the C3 job resource.

Durable from commit one — never in-memory-only (design doc F-5). Uses WAL journal
mode plus synchronous=FULL on the primary durability path: FULL fsyncs the WAL on
every commit, which is what actually protects against the "kill -9 mid-write"
class of loss that G1 (zero lost events across a restart) verifies. WAL alone
only changes *how* writes are logged, not whether they're synced to disk.

Schema mirrors `JobEntity` (android/ledger/src/main/java/com/vela/ledger/JobEntity.kt)
field-for-field — see README.md's mapping table for the authoritative comparison.
"""

from __future__ import annotations

import json
import sqlite3
import threading
import time
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

SCHEMA = """
CREATE TABLE IF NOT EXISTS jobs (
    job_id                          TEXT PRIMARY KEY,
    created_at                      INTEGER NOT NULL,
    updated_at                      INTEGER NOT NULL,
    origin_session_id                TEXT NOT NULL,
    origin_turn_id                   TEXT NOT NULL,
    origin_tool_call_id              TEXT NOT NULL UNIQUE,
    spec_json                        TEXT NOT NULL,
    status                           TEXT NOT NULL,
    attention_required               INTEGER NOT NULL DEFAULT 0,
    attention_reason                 TEXT,
    attention_options_json           TEXT,
    attention_deadline                INTEGER,
    progress_json                    TEXT NOT NULL DEFAULT '[]',
    result_json                      TEXT,
    cost_usd                         REAL,
    cost_tokens                      INTEGER,
    server_authoritative_version     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status);
CREATE INDEX IF NOT EXISTS idx_jobs_attention ON jobs(attention_required);
CREATE INDEX IF NOT EXISTS idx_jobs_created_at ON jobs(created_at);
"""

VALID_STATUSES = {
    "accepted",
    "running",
    "needs_attention",
    "blocked",
    "done",
    "failed",
    "cancelled",
}


class JobNotFoundError(Exception):
    pass


class DuplicateToolCallError(Exception):
    """Raised internally when a UNIQUE constraint on origin_tool_call_id fires.

    Callers should catch this and treat job creation as idempotent (G2): fetch
    and return the existing row rather than erroring the client.
    """


def now_ms() -> int:
    return int(time.time() * 1000)


@dataclass
class LedgerDB:
    """Thread-safe SQLite-backed durable store for job records.

    One connection per thread (SQLite connections are not thread-safe to share),
    guarded by a lock for write serialization. A file path (never ":memory:" in
    production use) is what makes this durable across process restarts.
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
            # FULL: fsync on every commit. This is the durability guarantee (G1) —
            # WAL mode alone does not imply fsync-on-commit.
            conn.execute("PRAGMA synchronous=FULL")
            conn.execute("PRAGMA foreign_keys=ON")
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

    # -- writes --------------------------------------------------------------

    def create_job(
        self,
        *,
        session_id: str,
        turn_id: str,
        tool_call_id: str,
        spec: dict[str, Any],
        status: str = "accepted",
    ) -> dict[str, Any]:
        """Create a job. Idempotent on origin.tool_call_id (G2): if a job already
        exists for this tool_call_id, returns the existing row unchanged rather
        than creating a duplicate.
        """
        if status not in VALID_STATUSES:
            raise ValueError(f"invalid status: {status!r}")

        existing = self.get_job_by_tool_call_id(tool_call_id)
        if existing is not None:
            return existing

        job_id = str(uuid.uuid4())
        ts = now_ms()
        with self._write_lock, self._cursor() as cur:
            try:
                cur.execute(
                    """
                    INSERT INTO jobs (
                        job_id, created_at, updated_at,
                        origin_session_id, origin_turn_id, origin_tool_call_id,
                        spec_json, status,
                        attention_required, attention_reason, attention_options_json,
                        attention_deadline, progress_json, result_json,
                        cost_usd, cost_tokens, server_authoritative_version
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, NULL, NULL, NULL, '[]', NULL, NULL, NULL, 1)
                    """,
                    (
                        job_id,
                        ts,
                        ts,
                        session_id,
                        turn_id,
                        tool_call_id,
                        json.dumps(spec),
                        status,
                    ),
                )
            except sqlite3.IntegrityError as exc:
                # Race: another writer created the same tool_call_id between our
                # SELECT and INSERT. Idempotency still holds — fetch and return it.
                if "origin_tool_call_id" in str(exc):
                    row = self.get_job_by_tool_call_id(tool_call_id)
                    if row is not None:
                        return row
                raise DuplicateToolCallError(str(exc)) from exc
        row = self.get_job(job_id)
        assert row is not None
        return row

    def update_job(
        self,
        job_id: str,
        *,
        status: str | None = None,
        progress_entry: dict[str, Any] | None = None,
        attention: dict[str, Any] | None = None,
        result: dict[str, Any] | None = None,
        cost: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Patch a job: status transition, append a progress entry, set attention,
        set result, and/or update cost. All are optional and composable, matching
        PATCH /ledger/jobs/{id} semantics from the fleet plane.
        """
        current = self.get_job(job_id)
        if current is None:
            raise JobNotFoundError(job_id)

        if status is not None and status not in VALID_STATUSES:
            raise ValueError(f"invalid status: {status!r}")

        new_status = status if status is not None else current["status"]

        progress = list(current["progress"])
        if progress_entry is not None:
            entry = {
                "ts": progress_entry.get("ts", now_ms()),
                "message": progress_entry["message"],
                "percent": progress_entry.get("percent"),
                "source": progress_entry.get("source", "fleet"),
            }
            progress.append(entry)

        if attention is not None:
            attention_required = bool(attention.get("required", False))
            attention_reason = attention.get("reason")
            attention_options = attention.get("options", [])
            attention_deadline = attention.get("deadline")
        else:
            attention_required = current["attention"]["required"]
            attention_reason = current["attention"]["reason"]
            attention_options = current["attention"]["options"]
            attention_deadline = current["attention"]["deadline"]

        result_json = (
            json.dumps(result) if result is not None else current.get("_result_json")
        )
        if result is None and current["result"] is not None:
            result_json = json.dumps(current["result"])

        cost_usd = cost.get("usd") if cost is not None else current["cost"]["usd"]
        cost_tokens = (
            cost.get("tokens") if cost is not None else current["cost"]["tokens"]
        )

        ts = now_ms()
        with self._write_lock, self._cursor() as cur:
            cur.execute(
                """
                UPDATE jobs SET
                    updated_at = ?,
                    status = ?,
                    progress_json = ?,
                    attention_required = ?,
                    attention_reason = ?,
                    attention_options_json = ?,
                    attention_deadline = ?,
                    result_json = ?,
                    cost_usd = ?,
                    cost_tokens = ?,
                    server_authoritative_version = server_authoritative_version + 1
                WHERE job_id = ?
                """,
                (
                    ts,
                    new_status,
                    json.dumps(progress),
                    1 if attention_required else 0,
                    attention_reason,
                    json.dumps(attention_options) if attention_options else None,
                    attention_deadline,
                    result_json,
                    cost_usd,
                    cost_tokens,
                    job_id,
                ),
            )
        row = self.get_job(job_id)
        assert row is not None
        return row

    def record_decision(
        self, job_id: str, *, new_status: str, decided_at: int | None = None
    ) -> dict[str, Any]:
        """Record a human decision: updates status, clears attention.required."""
        current = self.get_job(job_id)
        if current is None:
            raise JobNotFoundError(job_id)
        if new_status not in VALID_STATUSES:
            raise ValueError(f"invalid status: {new_status!r}")

        ts = decided_at if decided_at is not None else now_ms()
        with self._write_lock, self._cursor() as cur:
            cur.execute(
                """
                UPDATE jobs SET
                    updated_at = ?,
                    status = ?,
                    attention_required = 0,
                    server_authoritative_version = server_authoritative_version + 1
                WHERE job_id = ?
                """,
                (ts, new_status, job_id),
            )
        row = self.get_job(job_id)
        assert row is not None
        return row

    # -- reads -----------------------------------------------------------------

    def get_job(self, job_id: str) -> dict[str, Any] | None:
        with self._cursor() as cur:
            cur.execute("SELECT * FROM jobs WHERE job_id = ?", (job_id,))
            row = cur.fetchone()
        return self._row_to_record(row) if row is not None else None

    def get_job_by_tool_call_id(self, tool_call_id: str) -> dict[str, Any] | None:
        with self._cursor() as cur:
            cur.execute(
                "SELECT * FROM jobs WHERE origin_tool_call_id = ?", (tool_call_id,)
            )
            row = cur.fetchone()
        return self._row_to_record(row) if row is not None else None

    def list_jobs(
        self,
        *,
        status: str | None = None,
        attention_required: bool | None = None,
        since: int | None = None,
        limit: int = 200,
    ) -> list[dict[str, Any]]:
        clauses = []
        params: list[Any] = []
        if status is not None:
            clauses.append("status = ?")
            params.append(status)
        if attention_required is not None:
            clauses.append("attention_required = ?")
            params.append(1 if attention_required else 0)
        if since is not None:
            clauses.append("created_at >= ?")
            params.append(since)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        with self._cursor() as cur:
            cur.execute(
                f"SELECT * FROM jobs {where} ORDER BY created_at DESC LIMIT ?",
                (*params, limit),
            )
            rows = cur.fetchall()
        return [self._row_to_record(r) for r in rows]

    def attention_queue(self) -> list[dict[str, Any]]:
        return self.list_jobs(attention_required=True)

    @staticmethod
    def _row_to_record(row: sqlite3.Row) -> dict[str, Any]:
        result_json = row["result_json"]
        return {
            "job_id": row["job_id"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
            "origin": {
                "session_id": row["origin_session_id"],
                "turn_id": row["origin_turn_id"],
                "tool_call_id": row["origin_tool_call_id"],
            },
            "spec": json.loads(row["spec_json"]),
            "status": row["status"],
            "attention": {
                "required": bool(row["attention_required"]),
                "reason": row["attention_reason"],
                "options": json.loads(row["attention_options_json"])
                if row["attention_options_json"]
                else [],
                "deadline": row["attention_deadline"],
            },
            "progress": json.loads(row["progress_json"]),
            "result": json.loads(result_json) if result_json else None,
            "cost": {
                "usd": row["cost_usd"],
                "tokens": row["cost_tokens"],
            },
            "_result_json": result_json,
        }
