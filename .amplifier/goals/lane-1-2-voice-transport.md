# Lane 1.2 — voice-transport

**Outcome:** A `VoiceTransport` implementation (LiveKit Agents client) exists that provides a
full spoken round-trip against a stock LLM, with turn detection, barge-in, PTT fallback,
earcons, and a foreground service holding the session — implementing rules V1–V8 from the
design doc (§4.4).

**Working directory / branch / base SHA:** worktree only, branch `lane/1.2-voice-transport`,
base SHA `b4380a2e`. Work ONLY in this worktree.

**File ownership:** `android/voice/`, `android/app/src/main/.../service/VoiceForegroundService.kt`,
`voice-worker/` (the LiveKit-side Python worker). If you need to touch any file outside this
list — including `android/core-domain/`'s `VoiceTransport` interface itself — stop, record the
needed edit as a residual, do not make the edit; that interface is owned by lane 1.1 (already
merged) and changes to it must be flagged, not made unilaterally.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. LiveKit Agents client implemented behind the existing `VoiceTransport` interface
   (`android/core-domain/.../VoiceTransport.kt`) — no vendor types (LiveKit SDK types) may
   cross that interface boundary. Verify with a compile-time check (e.g. a lint rule or a
   dedicated unit test asserting the public API surface has zero LiveKit imports), not a
   code-review convention.
2. A full spoken round-trip works against a stock LLM: synthetic audio in via the emulator's
   gRPC `injectAudio`, synthetic TTS out captured via `streamAudio`, RMS above a silence
   threshold on the captured output (per the project's headless audio-testing approach).
3. Measured turn-taking latency: user-stops-speaking to assistant-begins-speaking, p50 < 800ms
   over at least 10 synthetic round-trips.
4. Barge-in interrupts TTS mid-sentence (V1/V2/V3).
5. PTT (push-to-talk) mode toggles and works as a fallback (V6).
6. Each of the three states (listening/thinking/speaking) emits a distinct earcon and updates
   the foreground-service notification (V7, V8).
7. The app survives a 20-minute continuous voice session without the foreground service being
   killed.

**Host capability limits:** this is a headless Linux machine — use the emulator's gRPC
`injectAudio`/`streamAudio` mechanism, never a real microphone or a human tester. If `/dev/kvm`
is unavailable (as found in lane 1.1), name that as BLOCKED for every emulator-dependent item
(2, 3, 4, 6, 7) individually rather than skipping silently, and still attempt item 1 and item 5
(PTT logic can be unit-tested without the emulator) plus the code-level parts of items that
don't strictly require a live emulator boot. **Spike first:** before deep implementation,
verify whether `injectAudio` behaves correctly under `-no-window` with no audio device
attached — this is foundational to this lane's entire verification strategy; if it fails, name
that conclusively as the blocker for the emulator-dependent items rather than guessing.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 8 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act (add to `.gitignore` if not
already present — it should be, from lane 1.1/1.4/S-1). Fields: `lane, session_id, verdict
(COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[], residuals[], pending_human[],
suite`.

## SCOPE-OUTS
- No host-tool implementation (calendar/notes/reminders/dispatch_to_fleet) — that is lane 1.3.
- No ledger implementation — that is lane 1.5.
- No real production voice-vendor account/billing setup — a stub/sandbox LiveKit config is
  sufficient if a real account isn't available; name that substitution explicitly if used.
- No Android Auto integration in this lane.
- No mid-turn steering (§8.5 known gap) — narration-only for now, this is an accepted,
  documented degradation per the design doc, not a defect to fix here.

## KNOWN
- This is Assumption A7 territory: turn-taking quality is believed to be the dominant driver
  of perceived product quality — budget verification effort on item 3 (latency) accordingly.
- Lane 1.1 already merged; the four domain interfaces exist at
  `android/core-domain/src/main/java/com/vela/core/domain/`.
- muxterm is available in this environment for isolated session/process management.
