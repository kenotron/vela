# amplifierd-jobs Design Spec

**Date:** 2026-05-07  
**Status:** Approved for implementation  
**Plugin:** `plugins/amplifierd-jobs/`

---

## Problem

amplifierd sessions are already excellent fire-and-forget executors — you submit a prompt, the AI works autonomously, you check the transcript later. What's missing is the **trigger layer**: automatically starting sessions on a schedule without a human initiating them.

This plugin adds that layer without touching the session system itself.

---

## Architecture

Two components in one package:

```
plugins/amplifierd-jobs/
├── pyproject.toml
├── amplifierd_jobs/                    ← server-side (plugin + scheduler + store)
│   ├── __init__.py
│   ├── models.py                       — Job, JobRun dataclasses
│   ├── store.py                        — SQLite persistence via aiosqlite
│   ├── scheduler.py                    — asyncio background scheduler
│   ├── router.py                       — FastAPI /api/jobs routes
│   └── plugin.py                       — amplifierd plugin entrypoint
└── amplifier_module_tool_jobs/         ← session-side (AI tool)
    └── __init__.py                     — mount() registers AI-callable tools
```

### How it wires

```
amplifierd startup
  → loads amplifierd_jobs plugin  (via uv pip install -e)
    → mounts /api/jobs router on the amplifierd FastAPI app
    → starts asyncio scheduler as a background task

vela bundle (vela.md)
  → loads tool-jobs module
    → AI in sessions can now call create_job(), list_jobs(), etc.

Vela app  ─────────────────────────────►  /api/jobs  (CRUD + manual trigger)
User asks "remind me every morning..."  →  AI calls create_job tool
                                                   ↓
                                          job registered in SQLite
                                                   ↓
                                     scheduler fires at scheduled time
                                                   ↓
                             POST /sessions + POST /sessions/{id}/execute/stream
                                                   ↓
                                     amplifierd session runs autonomously
```

The scheduler is a **consumer** of the session API — it hits the same endpoints Vela does. Sessions have no knowledge of the job system.

---

## Data Model

### Job

```python
@dataclass
class Job:
    id: str                    # uuid4, set at creation
    name: str                  # human label, e.g. "morning news summary"
    description: str = ""

    # ── Trigger ──────────────────────────────────────────────────────────────
    trigger_type: Literal["loop", "cron", "once"]
    schedule: str
    # "loop" → Go-style duration: "30m", "2h", "1h30m"
    # "cron" → standard 5-field cron: "0 9 * * 1-5"  (weekdays at 9am)
    # "once" → delay before single fire: "5m", "0" for immediate

    # ── Execution ─────────────────────────────────────────────────────────────
    prompt: str                # instruction sent to the session on each fire
    bundle_name: str = "vela"  # which bundle to use

    # ── Session mode ──────────────────────────────────────────────────────────
    session_mode: Literal["fresh", "persistent"]
    # "fresh"      → new session created on every trigger
    # "persistent" → one session reused across all triggers (context accumulates)
    persistent_session_id: str | None = None  # set after first run in persistent mode

    # ── State ─────────────────────────────────────────────────────────────────
    enabled: bool = True
    created_at: datetime = field(default_factory=datetime.utcnow)
    updated_at: datetime = field(default_factory=datetime.utcnow)
    next_run_at: datetime | None = None   # computed at schedule time, stored for API display
```

### JobRun

One record per execution. Append-only audit trail.

```python
@dataclass
class JobRun:
    id: str                    # uuid4
    job_id: str
    job_name: str              # denormalized — readable without a join
    session_id: str            # the amplifierd session that handled this run
    started_at: datetime
    ended_at: datetime | None = None
    status: Literal["running", "success", "failed", "cancelled"]
    source: Literal["scheduled", "manual"]
```

**No `output` field** — the session transcript IS the output. `session_id` lets the caller fetch the full result from `GET /sessions/{id}/transcript`. This avoids duplicating data and sidesteps loom's 64KB cap problem.

### SQLite Schema

```sql
CREATE TABLE jobs (
    id          TEXT PRIMARY KEY,
    data        TEXT NOT NULL,   -- JSON of Job dataclass
    updated_at  TEXT NOT NULL    -- for fast recency queries
);

CREATE TABLE job_runs (
    id          TEXT PRIMARY KEY,
    job_id      TEXT NOT NULL,
    data        TEXT NOT NULL,   -- JSON of JobRun dataclass
    started_at  TEXT NOT NULL,
    FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE
);

CREATE INDEX idx_job_runs_job_id ON job_runs(job_id, started_at DESC);
```

JSON-in-column keeps the schema stable as the dataclass evolves. The indexed columns cover the only query patterns needed.

---

## Scheduler

Pure asyncio — no threads, no external scheduler library required for loop/once. `croniter` used only for cron expression computation.

### Trigger implementations

**loop**
```python
async def run_loop(job: Job, interval: timedelta):
    while job.enabled:
        await fire(job)
        await asyncio.sleep(interval.total_seconds())
```

**cron**
```python
async def run_cron(job: Job, expr: str):
    while job.enabled:
        cron = croniter(expr, datetime.utcnow())
        next_dt = cron.get_next(datetime)
        job.next_run_at = next_dt
        delay = (next_dt - datetime.utcnow()).total_seconds()
        await asyncio.sleep(max(0, delay))
        if job.enabled:
            await fire(job)
```

**once**
```python
async def run_once(job: Job, delay: timedelta):
    await asyncio.sleep(delay.total_seconds())
    if job.enabled:
        await fire(job)
    job.enabled = False
    await store.save_job(job)
```

### Hot-reload

The scheduler keeps a `dict[job_id, asyncio.Task]` of running trigger tasks. CRUD operations cancel and restart the relevant task:
- `PUT /api/jobs/{id}` → cancel old task, start new one
- `DELETE /api/jobs/{id}` → cancel task, remove from dict
- `POST /api/jobs/{id}/enable` → start task
- `POST /api/jobs/{id}/disable` → cancel task

On startup: load all enabled jobs from SQLite, start a task for each.

### Session firing

```python
async def fire(job: Job, source: str = "scheduled"):
    run = JobRun(id=uuid4(), job_id=job.id, job_name=job.name,
                 source=source, status="running", started_at=datetime.utcnow())

    # Resolve session
    if job.session_mode == "fresh" or job.persistent_session_id is None:
        session_id = await create_session(job.bundle_name)
        if job.session_mode == "persistent":
            job.persistent_session_id = session_id
            await store.save_job(job)
    else:
        session_id = job.persistent_session_id

    run.session_id = session_id
    await store.save_run(run)

    # Execute — fire and observe
    await execute_prompt(session_id, job.prompt)
    status = await wait_for_completion(session_id)  # subscribes to GET /events

    run.status = status
    run.ended_at = datetime.utcnow()
    await store.save_run(run)
```

`wait_for_completion` subscribes to `GET /events?session={id}` and waits for `orchestrator:complete` or an error event. The coroutine stays alive during the session — which could be minutes — but asyncio handles this naturally.

---

## Session Modes

| Mode | First fire | Subsequent fires | Use for |
|------|-----------|-----------------|---------|
| `fresh` | Create new session | Create new session | Independent tasks, daily summaries, one-shot work |
| `persistent` | Create session, store its ID on the job | Reuse stored session ID | Ongoing monitors, context that should accumulate |

**Persistent mode caveat**: context grows unboundedly across runs. This is acceptable for V1 — the session transcript acts as the log, and the user can always delete and recreate the job to reset. A max-context reset strategy can be added later.

---

## REST API

All routes under `/api/jobs`. Auth via `x-amplifier-token` (same as all amplifierd routes).

```
# Job CRUD
GET    /api/jobs                  list all jobs (with computed next_run_at)
POST   /api/jobs                  create job + schedule it immediately
GET    /api/jobs/{id}             get job
PUT    /api/jobs/{id}             update job + hot-reload scheduler
DELETE /api/jobs/{id}             delete job + cancel scheduler task

# Job control
POST   /api/jobs/{id}/trigger     manual fire (source="manual"), returns run_id
POST   /api/jobs/{id}/enable      re-enable + restart scheduler
POST   /api/jobs/{id}/disable     disable + cancel scheduler task

# Runs
GET    /api/jobs/{id}/runs        runs for a specific job (most recent first)
GET    /api/jobs/runs             all recent runs across all jobs
GET    /api/jobs/runs/{run_id}    single run detail (includes session_id for transcript link)
```

**`POST /api/jobs` request body:**
```json
{
  "name": "morning standup summary",
  "trigger_type": "cron",
  "schedule": "0 9 * * 1-5",
  "prompt": "Check my calendar and Slack, summarize what I need to do today",
  "bundle_name": "vela",
  "session_mode": "fresh"
}
```

---

## AI Tool Interface

Registered by `amplifier_module_tool_jobs` when the `tool-jobs` module is loaded in the vela bundle. These are the functions the AI can call when a user asks to schedule work.

```python
async def create_job(
    name: str,
    trigger_type: Literal["loop", "cron", "once"],
    schedule: str,            # "30m" | "0 9 * * *" | "5m"
    prompt: str,
    session_mode: Literal["fresh", "persistent"] = "fresh",
    bundle_name: str = "vela",
    description: str = "",
) -> dict:
    """Register a scheduled background job. Returns the created job."""

async def list_jobs() -> list[dict]:
    """List all registered jobs with their schedules and last run status."""

async def get_job(job_id: str) -> dict:
    """Get a specific job by ID."""

async def delete_job(job_id: str) -> dict:
    """Delete a job and cancel its schedule."""

async def trigger_job(job_id: str) -> dict:
    """Manually fire a job immediately. Returns run_id."""

async def disable_job(job_id: str) -> dict:
    """Pause a job without deleting it."""

async def enable_job(job_id: str) -> dict:
    """Re-enable a paused job."""
```

**Example interaction:**
```
User: "remind me every weekday morning at 9am to check my emails and give me a summary"

AI calls: create_job(
    name="morning email summary",
    trigger_type="cron",
    schedule="0 9 * * 1-5",
    prompt="Check my recent emails and summarize anything that needs attention today",
    session_mode="fresh",
)

AI responds: "Done. I've scheduled a daily email summary every weekday at 9am.
              Each morning a fresh session will run and build a summary — you can
              check back anytime by asking me to show recent job runs."
```

---

## Installation & Wiring

### Install the plugin

```bash
# Install amplifierd plugin (server-side router + scheduler)
uv pip install -e plugins/amplifierd-jobs \
  --python ~/.local/share/uv/tools/amplifierd/bin/python

# Install AI tool module (session-side tools)
# (already handled by the same package — both components in one install)
```

### Register with amplifierd

In `~/.amplifierd/start-amplifierd.sh`, add after server starts:

```bash
# Register jobs plugin
curl -sf -H "x-amplifier-token: $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"jobs","module":"amplifierd_jobs.plugin"}' \
  http://127.0.0.1:8410/plugins/register
```

### Add tool to vela bundle

In `~/.amplifier/bundles/vela.md`:

```yaml
session:
  tools:
    - module: tool-jobs
      source: /Users/ken/workspace/vela/plugins/amplifierd-jobs
      config:
        amplifierd_url: http://127.0.0.1:8410
        token: ${VELA_AUTH_TOKEN}
```

### Database location

```
~/.amplifierd/jobs.db   — SQLite file, created on first run
```

---

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Session creation fails | `JobRun.status = "failed"`, scheduler continues |
| Session execution hangs | `wait_for_completion` has a configurable timeout (default: 30min); marks run `failed` |
| amplifierd restart | Scheduler reloads all enabled jobs from SQLite on startup; any `running` runs get marked `failed` with a note |
| `persistent` session deleted externally | Next fire gets a 404; plugin creates a new session and updates `persistent_session_id` |
| croniter parse error on job creation | Validated at `POST /api/jobs` time, returns 400 |

---

## Non-Goals (V1)

- **File watch / event triggers** — future work once time-based triggers prove out
- **Retry with backoff** — sessions handle their own retries internally; job-level retry adds complexity without clear benefit yet
- **Dead letter queue / alerting** — failure is visible in run history; notification hook can come later
- **Priority queue** — all jobs compete equally for execution
- **Output capture on JobRun** — session transcript serves this purpose; no duplication

---

## Resolved Design Decisions

1. **Concurrency limit**: yes — default max 4 parallel executing jobs, configurable via plugin
   config (`max_parallel: 4`). Enforced by an asyncio semaphore in the scheduler. Jobs that
   exceed the cap queue until a slot frees; the queue depth is visible on the status endpoint.

2. **`once` job cleanup**: disabled `once` jobs stay in the list forever — deletion is always
   explicit. They provide audit trail and make it easy to re-trigger the same job manually later.

3. **Last run status on responses**: `list_jobs` and `get_job` include `last_run_at` and
   `last_run_status` denormalized on the Job response. The AI can answer "did my morning
   summary run today?" without a separate runs query. These fields are updated in place on the
   Job row when a run completes (no join needed at read time).
