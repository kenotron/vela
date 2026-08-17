# vela-voice-worker

LiveKit Agents worker implementing the server side of the spoken round trip
for Lane 1.2 (voice-transport): joins a LiveKit room, runs STT -> a stock LLM
-> TTS, using LiveKit Agents' built-in **semantic** turn-detection model (V2)
and never a raw silence-timer VAD, with no preemptive/speculative generation
before the user's turn completes (V3).

## Layout

- `voice_worker/turn_detection.py` - pure-Python config object enforcing V2
  (semantic strategy only) and V3 (no preemptive generation) at construction
  time. No LiveKit/network dependency; fully unit tested.
- `voice_worker/config.py` - worker configuration (LiveKit URL/keys, stock LLM
  model name), loaded from environment with sandbox-stub fallbacks. No
  LiveKit/network dependency; fully unit tested.
- `voice_worker/agent.py` - the actual LiveKit Agents entrypoint: wires STT,
  stock LLM, TTS, and the semantic turn detector (`MultilingualModel`) into an
  `AgentSession`, and starts the CLI worker runtime.
- `voice_worker/tests/` - unit tests, runnable fully offline (see below).

## Sandbox substitution (explicit, per lane SCOPE-OUTS)

No real production LiveKit vendor account or billing setup was available in
this session. `voice_worker/config.py` falls back to explicitly-fake sandbox
values (`wss://sandbox.invalid.local`, `sandbox-stub-key`,
`sandbox-stub-secret`) when the real `LIVEKIT_URL` / `LIVEKIT_API_KEY` /
`LIVEKIT_API_SECRET` environment variables are not set. These stub values are
**not** connectable to any real LiveKit deployment - they exist purely so the
worker's configuration-loading code path is exercised and testable without
requiring real credentials. To run against a real LiveKit server, set the
three environment variables to real values before starting the worker.

The LLM is "stock" per the goal spec (item 2: "a full spoken round-trip works
against a stock LLM") - `voice_worker/agent.py` wires a stock OpenAI-compatible
LLM plugin (`openai.LLM`, defaulting to `gpt-4o-mini`, overridable via
`VELA_STOCK_LLM_MODEL`) with no fine-tuning or custom prompt-injection layer
beyond what LiveKit Agents provides out of the box.

## What was and wasn't verified in this session

**Verified (offline, no network, no LiveKit server required):**
- `voice_worker/turn_detection.py`: semantic-only strategy enforcement, and
  the no-preemptive-generation invariant, both verified by unit tests that
  construct the config object directly and assert it raises for disallowed
  configurations.
- `voice_worker/config.py`: environment-variable loading and sandbox-stub
  fallback behavior, verified by unit tests using `pytest`'s `monkeypatch`.

**NOT verified in this session (requires network + a reachable LiveKit
server, neither available in this sandboxed, no-`/dev/kvm` environment):**
- `voice_worker/agent.py`'s actual room connection, STT/LLM/TTS plugin
  behavior, and end-to-end audio round trip. This file is structurally
  complete and represents the intended production wiring (matching the
  patterns in LiveKit Agents' own examples), but has not been executed
  against a live room in this session. `livekit-agents` and
  `livekit-plugins-turn-detector` may also not be installable at all without
  network access - if `pip install -e .[dev]` fails to resolve these
  packages in your environment, the pure-Python modules above (and their
  tests) remain independently runnable since they have zero dependency on the
  `livekit` packages.
- Full spoken round-trip via emulator `injectAudio`/`streamAudio`, turn-taking
  latency measurement, barge-in interruption of TTS mid-sentence, and 20-minute
  session survival are all Android-app-side / emulator-dependent verifications
  owned by the `android/voice` half of this lane, not this worker - see the
  lane's final summary for their BLOCKED status (no `/dev/kvm` on this host).

## Running tests

```bash
cd voice-worker
python3 -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"   # or: pip install pytest  (if livekit packages can't resolve offline)
pytest voice_worker/tests -v
```

If `livekit-agents` / `livekit-plugins-turn-detector` cannot be installed
offline, install just `pytest` and run the tests directly - `turn_detection.py`
and `config.py` import nothing from the `livekit` namespace, so their tests
still pass. `agent.py` (which does import `livekit.agents`/`livekit.plugins`)
would fail to import in that case; this is expected and documented above.
