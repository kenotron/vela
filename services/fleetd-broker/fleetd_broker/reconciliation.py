"""Reconnect reconciliation (design doc 4.1's "reconciliation" responsibility;
README.md residual "Reconciliation on worker reconnect").

When a worker reconnects after a gap, it reports the job ids it still
believes it is running (the `register` message's `job_ids` field -- see
app.py's websocket handler). The broker's own bindings (`SessionTable`)
may disagree, because time passed while the connection was down:

- A job the broker still has bound to this machine, but the worker no
  longer reports -- the job died (or the worker gave up on it) while
  disconnected. The broker cannot silently keep tracking it forever, so it
  is marked `failed` in the ledger and unbound.
- A job the worker reports that the broker did not have bound (e.g. the
  broker itself restarted and lost its in-memory bindings before the
  durable store recovered them, or a job dispatched by a broker instance
  that never got the `bind_job` call persisted before a crash) -- resumed:
  rebind it so decision relay and future reconciliation both see it.

This is deliberately narrow: it is a diff-and-correct step, not a full
job-state machine. It does not decide *why* a job vanished, only that the
broker's bookkeeping must not silently diverge from what the worker
reports.
"""

from __future__ import annotations

from dataclasses import dataclass, field

from fleetd_broker.ledger_client import LedgerClient
from fleetd_broker.registry import WorkerRegistry
from fleetd_broker.sessions import SessionTable


@dataclass
class ReconciliationResult:
    resumed: list[str] = field(default_factory=list)
    vanished: list[str] = field(default_factory=list)


class Reconciler:
    def __init__(
        self,
        *,
        registry: WorkerRegistry,
        sessions: SessionTable,
        ledger: LedgerClient,
    ) -> None:
        self._registry = registry
        self._sessions = sessions
        self._ledger = ledger

    async def reconcile(
        self, machine_id: str, reported_job_ids: list[str]
    ) -> ReconciliationResult:
        reported = set(reported_job_ids)
        known = self._sessions.jobs_for_machine(machine_id)

        result = ReconciliationResult()

        # Jobs the broker still thinks are running here, but the worker no
        # longer reports: they vanished while disconnected. Mark failed
        # (never silently drop) and unbind so relay/registry stop tracking
        # them.
        for job_id in sorted(known - reported):
            await self._ledger.patch_job(
                job_id,
                status="failed",
                result={
                    "error": (
                        f"worker {machine_id} reconnected without this job "
                        "in its reported job set; marked failed by "
                        "reconciliation"
                    )
                },
            )
            self._sessions.unbind_job(job_id)
            self._registry.increment_active_jobs(machine_id, -1)
            result.vanished.append(job_id)

        # Jobs the worker reports that the broker had no binding for
        # (e.g. broker-side state lost before the durable store caught up):
        # resume tracking so relay/reconciliation work going forward.
        for job_id in sorted(reported - known):
            self._sessions.bind_job(job_id, machine_id)
            result.resumed.append(job_id)

        return result
