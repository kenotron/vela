# Vela — DESIGN.md

> **Source of truth for AI UI generation.** This document is the complete design language for Vela. Feed it directly to Stitch, v0, or any UI generator as a prefix prompt. Every decision is paired with evocative prose (so the model *feels* the design) and concrete tokens (so the model *renders* it correctly). When in tension, **specificity wins** — but never at the cost of expressiveness.

> Built for **Material 3 Expressive**. Not Material 2. Not generic dark Android. Not a ChatGPT clone.

---

## 1. Design Philosophy

### The Emotional Brief

**Visual Description**: Vela is a **cockpit, not a conversation**. When you open the app, you should feel like you've sat down at the captain's console of a small fleet of intelligent ships. The phone is not the AI — the phone is the **bridge**. Compute happens elsewhere, in nodes scattered across the network, and Vela is the calm, instrumented surface that lets you direct them with your voice.

The mood is **composed but alive**. Surfaces breathe. Status carries color the way a runway carries lights — semantic, immediate, unmistakable. There is generous negative space, but never sterile space; the dark backdrop has the quiet warmth of a planetarium ceiling, not the dead chill of a void. Type is large where authority matters (a node's name, a session title) and quiet where data is dense. Shapes are big and confident — soft tiles that feel substantial enough to press, not flimsy chips that feel like they'd flick away.

The interaction model is **command, not chat**. The voice FAB is the protagonist of every screen. Touching it should feel like opening a comms channel; releasing it should feel like the channel snapping closed. The rest of the UI is the readout — what is happening, what is waiting on you, what is done.

Above all: **the user is the operator, the AI is the crew**. The design must never grovel, never personify the AI as a friendly chatbot, never beg for engagement. It reports. It awaits orders. It glows when work is in motion.

**Values**:
- **Mood tokens**: `mood/composed`, `mood/alive`, `mood/instrumented`, `mood/operator-grade`
- **Brightness target**: dark-mode-only at launch. App-wide luminance baseline ~6% (deep), never crossing 12% on resting surfaces.
- **Voice-first hierarchy**: Mic FAB is always the highest-z, highest-contrast element on screen. Everything else defers.
- **Density**: Comfortable, not compact. 16dp gutters, 24dp section spacing, 12dp inter-card spacing.
- **Forbidden moods**: `chatbot`, `assistant-friendly`, `productivity-clean`, `cyberpunk-neon`, `enterprise-dashboard`.

---

## 2. Color System

The palette is built around a **deep blue-indigo midnight ground**, with **semantic status colors** that carry weight (not decoration). Color in Vela means something. If a surface is amber, it is *running*. If a card is violet, it *needs you*. Color is never used for personality alone.

### 2.1 Background & Surface Tones

#### `surface/abyss` — App Background
**Visual Description**: A deep blue-indigo that feels like the sky at 30,000 feet on a moonless night — not black, not navy, but the color of held breath. It has just enough chroma to feel intentional, just enough darkness to make any color placed on it sing. Never reads as "dark mode" — reads as *night ops*.
**Values**: `#0B0E1A` — full app background. No gradient. No noise texture. No vignette.

#### `surface/sub` — Resting Card Surface
**Visual Description**: One step lifted from abyss, with a faint warm-cool shift toward indigo. This is where most cards live. Looks like the abyss thinking about becoming a surface.
**Values**: `#11152A` — default M3 `surfaceContainer`. Cards, sheets, list items.

#### `surface/raised` — Elevated Card / Sheet
**Visual Description**: Where attention goes. Slightly lighter, slightly bluer. The surface a session card uses when expanded, the color of a tool-call card.
**Values**: `#171C36` — M3 `surfaceContainerHigh`. Used for expanded states, modals, bottom sheets.

#### `surface/peak` — Top-of-Stack
**Visual Description**: Reserved for the topmost overlay — a sheet on top of a sheet, the approval gate, the voice overlay's inner ring. Lightest indigo in the system, but still unmistakably night.
**Values**: `#1F2542` — M3 `surfaceContainerHighest`.

#### `surface/coordinator` — The Distinct Coordinator Tone
**Visual Description**: Coordinator sessions feel **different**. They get a surface tinted toward deep teal-cyan — cooler, more "control room," subtly signaling: *this is not one node speaking, this is the conductor*. When you swipe into a coordinator session, the entire surface temperature shifts and you should feel it.
**Values**: `#0C1E26` background, `#13303C` for coordinator session cards. Apply a subtle 1dp inner stroke at `#1FE0C2` 12% alpha to coordinator surfaces.

### 2.2 Semantic Status Colors

These four colors are **load-bearing**. They are never used for branding, never used decoratively, never swapped for variety. If you see amber in Vela, work is running. Period.

#### `status/running` — Warm Amber-Gold
**Visual Description**: The color of a tungsten filament, the color of a turbine spinning under load. Warm, alive, kinetic. **Not blue**. Blue is what every other AI app does, and blue says "calm idle." Vela's running state should feel like *energy in motion* — somewhere between honey and fire. When this color appears on a card, you should feel the work happening.
**Values**:
- Primary: `#F5A524`
- On-color text: `#1A1000`
- Container: `#3A2400`
- On-container: `#FFD89B`
- **Glow halo for running cards**: `#F5A524` at 18% alpha, 24dp radius, behind the card.

#### `status/waiting` — Electric Violet
**Visual Description**: Demands attention without panicking you. Violet says *"a decision is needed"* in a way yellow cannot — yellow is caution, violet is **summoning**. It feels almost UV — synthetic, clean, slightly otherworldly. When a session is parked at an approval gate, this color pulses gently at the card's leading edge.
**Values**:
- Primary: `#A78BFA`
- On-color text: `#1A0F3A`
- Container: `#2E1A5C`
- On-container: `#DDD0FF`
- **Pulse**: 1.4s cycle, 60%→100% alpha, ease-in-out-sine.

#### `status/done` — Quiet Sage Green
**Visual Description**: Completion should feel **settled**, not celebratory. Not the shrill green of a checkmark in a productivity app — a more muted, vegetal sage. The color of "your shift is over." It tells you the work landed without demanding a high-five.
**Values**:
- Primary: `#7DCFA5`
- On-color text: `#0A2418`
- Container: `#143A29`
- On-container: `#B8E8CD`

#### `status/error` — Coral, not Red
**Visual Description**: Generic red is brutal and feels punitive. Vela's error state is a warm **coral** — serious, immediate, but not aggressive. It says *"something went wrong, here's what"* without screaming. Reserved for actual failures, never for warnings.
**Values**:
- Primary: `#FF6B6B`
- On-color text: `#2A0808`
- Container: `#4A1818`
- On-container: `#FFC4C4`

### 2.3 Identity & Accent Colors

#### `accent/vela` — The Brand Accent
**Visual Description**: A precise, slightly cool **cyan-aqua**. Used sparingly: the Vela wordmark, the leading edge of the voice FAB at rest, selection chrome, focused borders. Never on more than one element per screen. It is the app's signature note.
**Values**: `#5EEAD4`. Used at 100% for active states, 40% for borders, never as a fill on large surfaces.

#### `accent/coordinator` — Coordinator-Mode Accent
**Visual Description**: A teal-leaning sibling to the brand accent — distinguishes coordinator views without competing.
**Values**: `#1FE0C2`.

### 2.4 Text Colors

#### `text/primary`
**Visual Description**: Bright but not white-hot. A near-white with a faint warm cast so it doesn't feel surgical against the indigo.
**Values**: `#F5F2EC`. Used for titles, body, primary content.

#### `text/secondary`
**Visual Description**: For metadata, timestamps, status labels. Distinctly quieter, but never illegible.
**Values**: `#B5B8C8`.

#### `text/tertiary`
**Visual Description**: Captions, hint text, placeholder.
**Values**: `#7A7E94`.

#### `text/disabled`
**Values**: `#4A4D5E`.

### 2.5 Strokes & Dividers

#### `stroke/hairline`
**Visual Description**: A whisper of a line — used to separate list items where a real divider would be too loud.
**Values**: `#FFFFFF` at 6% alpha (`#FFFFFF0F`), 1dp.

#### `stroke/edge`
**Visual Description**: A real border — used on focused inputs, the voice overlay ring at idle, coordinator surfaces.
**Values**: `#FFFFFF` at 12% alpha, 1dp.

---

## 3. Typography

Vela uses **two typefaces** in deliberate contrast.

- **Display & Headlines**: **Instrument Serif** (or fallback: **Newsreader**). A high-contrast serif gives node and session names *gravitas* — these are named entities you control, not generic items in a list. Serif on dark indigo at display scale feels editorial, considered, never trendy.
- **Body, Labels, UI Chrome**: **Inter** (variable weight 400–700). Neutral, legible, gets out of the way.
- **Monospace** (tool I/O, code, IDs): **JetBrains Mono**. Used inside tool-call cards, never in titles.

### Type Scale

#### `type/display-l` — Hero Titles (Node Detail Screen Hero)
**Visual Description**: When you tap into a node, its name is rendered like the title page of a book. This is the moment that sells the design. Not 24sp, not 32sp — *display scale*. The serif's contrast lets it sit beautifully on indigo.
**Values**: Instrument Serif, **48sp**, weight 400 (regular — let the serif itself carry the drama, not bold), tracking -1.5%, line-height 52sp. Color `text/primary`.

#### `type/display-m` — Session Detail Title
**Visual Description**: Slightly smaller than the node hero, but still authoritative. The session's title or first prompt, anchored at the top of the session screen, framed with breathing room.
**Values**: Instrument Serif, **36sp**, weight 400, tracking -1%, line-height 40sp.

#### `type/headline-l` — Home List Node Names
**Visual Description**: The node name on a home-screen card is rendered at headline scale. **Not body scale.** This is the single most important signal on the home screen — *which node are you talking to?* It deserves size.
**Values**: Instrument Serif, **28sp**, weight 400, tracking -0.5%, line-height 32sp.

#### `type/headline-m` — Section Titles
**Visual Description**: "Projects," "Active Sessions," "Coordinator." The dividers of the app's hierarchy.
**Values**: Inter, **22sp**, weight 600, tracking 0, line-height 26sp. Color `text/primary`.

#### `type/title-l` — Card Titles (Session card title, Approval gate title)
**Values**: Inter, **18sp**, weight 600, line-height 22sp.

#### `type/title-m` — Subtle card titles, Tool-call card title
**Values**: Inter, **15sp**, weight 600, line-height 20sp.

#### `type/body-l` — Default body
**Values**: Inter, **16sp**, weight 400, line-height 22sp. Color `text/primary`.

#### `type/body-m` — Secondary body, card descriptions
**Values**: Inter, **14sp**, weight 400, line-height 20sp. Color `text/secondary`.

#### `type/label-l` — Buttons, status chips
**Values**: Inter, **14sp**, weight 600, tracking +0.5%, line-height 16sp.

#### `type/label-m` — Metadata, timestamps, tool-call args labels
**Values**: Inter, **12sp**, weight 500, tracking +1%, line-height 14sp. Color `text/tertiary`.

#### `type/mono-m` — Tool I/O, code blocks
**Values**: JetBrains Mono, **13sp**, weight 400, line-height 20sp. Color `text/primary` on `surface/raised`.

#### `type/caption-uppercase` — Status chips, section eyebrows
**Visual Description**: All-caps, tracked-out, small. The ham-radio readout note.
**Values**: Inter, **11sp**, weight 700, tracking +8%, ALL CAPS, line-height 14sp.

---

## 4. Shape & Containment

Shape carries **status and importance**. Big, soft, confident shapes for things you press; tighter shapes for dense data; circles for voice. **No timid 8dp rounded rectangles anywhere in this app.**

### `shape/tile` — Node Cards (the substantial ones)
**Visual Description**: A node card is a *tile*. It feels like a piece of polished slate you could pick up. Generous radius, full bleed in the safe area, a sense of weight. The radius is intentionally large so the card reads as an object, not a row.
**Values**: 28dp corner radius, all corners. Full width minus 16dp gutters. Minimum height 120dp. Internal padding 20dp.

### `shape/card` — Session Cards, Tool-Call Cards
**Visual Description**: More contained, more list-like, but still distinctly soft. Reads as an item in a hierarchy, not a tile.
**Values**: 20dp corner radius, all corners. Full width minus 16dp gutters. Internal padding 16dp.

### `shape/chip` — Status Chips, Filter Chips
**Visual Description**: Pill-shaped, snug, never a rectangle.
**Values**: Fully rounded (height/2 radius). Height 28dp. Horizontal padding 12dp.

### `shape/sheet` — Bottom Sheets, Approval Gate
**Visual Description**: Top corners only — the sheet *rises* from the bottom edge.
**Values**: 32dp top-left, 32dp top-right, 0dp bottom corners. Drag handle 36dp wide × 4dp tall, `text/tertiary`, centered 12dp from top.

### `shape/circle` — Voice FAB, Voice Overlay, Avatar Discs
**Visual Description**: Pure circles. The voice language is entirely circular; no rounded squares for any voice-adjacent UI.
**Values**: 100% rounded. See §7.7 for FAB sizes.

### `shape/branch` — Coordinator Branch Card
**Visual Description**: A coordinator branch card has **asymmetric** rounding — fully rounded on the leading edge (left), tighter on the trailing edge — so it reads as a "lane" coming from the conductor. This subtle asymmetry signals "this is part of a parallel graph, not a freestanding card."
**Values**: 24dp leading-top + leading-bottom radius, 8dp trailing-top + trailing-bottom radius. Internal padding 16dp.

### Containment Strategy

- **Stroke vs fill**: Default is **fill, no stroke**. Strokes appear only on (1) focused inputs, (2) coordinator surfaces, (3) the voice overlay idle ring.
- **Nested cards**: Allowed up to 2 levels deep (session card containing tool-call cards). Nested cards step up one surface tone (`sub` → `raised`).
- **No shadows on cards.** Elevation is communicated by **surface tone**, not by Material 2-style drop shadows. The single exception is the voice FAB (see §7.7).

---

## 5. Surface & Elevation

Vela uses **tonal elevation**, not shadow elevation. Depth is communicated by the surface stepping toward lighter indigo. This keeps the dark mode legitimately dark — no shadow blur halos that quietly turn the screen into mush.

### Elevation Levels

| Level | Surface Token | Hex | Use |
|---|---|---|---|
| 0 | `surface/abyss` | `#0B0E1A` | App background |
| 1 | `surface/sub` | `#11152A` | Default cards, list items |
| 2 | `surface/raised` | `#171C36` | Expanded cards, sheets, modals |
| 3 | `surface/peak` | `#1F2542` | Top overlay, voice overlay inner ring, sheets-over-sheets |

**Visual Description**: Moving up the stack feels like pages of indigo paper getting closer to a faint light source above. Never harsh, never flat — there is always a step of difference between adjacent layers, which is what lets you parse the hierarchy at a glance.

### The One Exception: Voice FAB Glow

**Visual Description**: The Voice FAB is the *only* element in the system with a true glow. When active, a soft outer halo of `status/running` amber radiates beneath it — physical, warm, like a coal in the dark. This is what tells you, from across the room, that work is being done.
**Values**: 32dp radial blur, `#F5A524` at 24% alpha when running, `#5EEAD4` at 18% alpha when idle-ready, no glow when disabled.

---

## 6. Motion

Motion in Vela is **physical**. Spring physics, never linear tweens. Surfaces feel like they have mass; transitions feel like they obey gravity. M3 Expressive springs, dampened, with deliberate intent.

### Motion Tokens

#### `motion/spring-snappy` — Card press, chip toggle
**Visual Description**: The crispest spring in the system. Reacts immediately, settles fast. Used for direct manipulation.
**Values**: stiffness 700, damping 28. Approx 220ms total.

#### `motion/spring-standard` — Sheet rise, screen transitions
**Visual Description**: The default. Has a touch of overshoot — enough to feel alive, not so much it feels silly.
**Values**: stiffness 380, damping 30. Approx 360ms total.

#### `motion/spring-expressive` — Tool-call card expand, approval gate appear
**Visual Description**: Slower, more theatrical. The card *unfolds*. There's a beat of overshoot at the end. This is M3 Expressive's signature — surfaces that announce themselves.
**Values**: stiffness 240, damping 26. Approx 480ms total.

#### `motion/spring-voice` — Voice overlay grow/shrink
**Visual Description**: The voice overlay *blooms*. The mic FAB scales up into a full-screen circle that radiates from the FAB's position, not from the screen center. Think: a drop of ink released into water, but contained.
**Values**: stiffness 200, damping 24, plus a 60ms anticipation phase where the FAB shrinks 4dp before expanding. Approx 520ms total.

### Key Transitions

#### Home → Node Detail
**Visual Description**: The tapped node card lifts and expands; its title shifts up and grows from `headline-l` (28sp) to `display-l` (48sp); the rest of the home list fades down and away. Hero transition on the title text.
**Values**: Title shared-element transform, `motion/spring-standard`, 360ms. List items stagger-fade out, 40ms stagger, 200ms each.

#### Node → Session
**Visual Description**: Forward push, but with depth — the node screen recedes and tints darker behind the new session screen, which slides up from the bottom edge with a faint scale-from-95%.
**Values**: Outgoing screen scales to 0.96 and tints `#000000` at 30% alpha. Incoming screen rises with `motion/spring-standard`.

#### Tool Call Card Expand
**Visual Description**: The card grows in place, content fades in 80ms after the size animation begins (so the box settles before the content arrives). A subtle +2dp surface-tone bump accompanies the expand.
**Values**: `motion/spring-expressive` for height. Content opacity 0→1, ease-out, starts at t=80ms, duration 200ms.

#### Voice FAB Press → Voice Overlay
**Visual Description**: The FAB anticipates (4dp shrink, 60ms), then *blooms* outward into the full-screen voice overlay. The bloom origin is the FAB's center, not the screen's. Background dims to 80% indigo.
**Values**: `motion/spring-voice`, scale FAB 1× → screen-circumscribing radius. Background fade in parallel, 280ms.

#### Status Change: running → done
**Visual Description**: The amber halo of the running card breathes once more, then fades; the surface tone shifts from amber-tinted to sage-tinted over a deliberate beat. The user should feel the work *land*.
**Values**: 600ms cross-fade between status colors, ease-in-out. Halo fades 800ms.

### Breathing Animation (Active States)

#### Voice FAB Breathing (when a session on this node is running)
**Visual Description**: The outer glow ring slowly inhales and exhales — barely perceptible, never distracting, but unmistakable when you notice it. Tells you, peripherally, that something is alive.
**Values**: Scale 1.00 → 1.06, opacity 100% → 70%, 2.4s cycle, ease-in-out-sine, infinite.

#### Running Session Card Pulse
**Visual Description**: The leading-edge accent stripe of a running session card pulses at the same cadence as the FAB. This visual rhyme is intentional — the FAB and the active card breathe together.
**Values**: 4dp leading stripe, opacity 70% → 100%, 2.4s cycle, in phase with FAB.

### Forbidden Motion
- **No linear easing.** Anywhere. Springs or cubic-bezier only.
- **No bounce-bounce-bounce overshoot.** One overshoot beat, max.
- **No fades shorter than 120ms.** Looks twitchy.
- **No "shimmer" loading skeletons.** Use a calm tonal pulse instead.

---

## 7. Key Component Definitions

### 7.1 Node Card (Home Screen)

**Visual Description**: A node card is a substantial **tile** — large, full-width, with the node's name set at headline scale in serif. It feels like a slab of polished slate with the node's identity engraved into it. The leading edge carries a thin 4dp colored stripe matching the node's current dominant status (amber if any session is running on this node, violet if any is awaiting approval, sage if all are done, default `accent/vela` if idle). A small node-glyph (avatar disc, 40dp) sits in the top-left. Below the name: a single line of telemetry — `3 active · 1 awaiting · last seen 2m ago` — in `type/body-m`, `text/secondary`. Bottom-right corner: a small circular live-indicator dot. Press feedback is a satisfying scale-down to 98% with a brief surface-tone lift.

**Values**:
- Container: `surface/sub` `#11152A`, `shape/tile` 28dp radius, full-width minus 16dp gutters, min-height 120dp, padding 20dp.
- Leading status stripe: 4dp wide, full height, color = current status color, anchored to leading edge inset by 0dp (flush).
- Avatar disc: 40dp circle, top-left, 12dp gap to node name.
- Node name: `type/headline-l` (Instrument Serif 28sp, regular).
- Telemetry line: `type/body-m`, `text/secondary`, 8dp below name.
- Live-indicator dot: 8dp circle, bottom-right, color = status color, with 2dp ring of `surface/sub` separating it from card, breathing if active.
- Press: scale 0.98, surface tone +1 step, `motion/spring-snappy`.
- Inter-card spacing: 12dp vertical.

### 7.2 Session Card (4 Status States)

**Visual Description (base)**: More contained than a node card. Shows session title, last-activity timestamp, a status chip, and a thin progress affordance. The leading edge carries a 4dp status stripe — same language as node cards but smaller. Tap expands into the session detail screen.

**Common Values**:
- Container: `surface/sub`, `shape/card` 20dp radius, padding 16dp.
- Title: `type/title-l` (Inter 18sp/600), `text/primary`, max 2 lines, ellipsis.
- Timestamp: `type/label-m`, `text/tertiary`, top-right.
- Leading stripe: 4dp wide, status color.
- Status chip: `shape/chip`, `type/caption-uppercase`, container = status container color, text = on-container.

#### State: `running`
**Visual Description**: Card carries a soft amber halo behind it (24dp blur, 18% alpha) — the *heat* of work in motion. The leading stripe pulses gently in sync with the Voice FAB. Status chip reads `RUNNING` in amber. A small spinner-glyph (a thin amber arc rotating slowly, 1.6s/rev) sits to the right of the title.
**Values**: Halo `#F5A524` 18% alpha, 24dp radius. Stripe pulses (see §6 breathing). Chip container `#3A2400`, text `#FFD89B`. Spinner: 16dp, stroke 1.5dp, color `status/running`, 1.6s linear rotate (this is the one place linear motion is permitted — it's a mechanical indicator, not a transition).

#### State: `waiting` (Approval Required)
**Visual Description**: The card *summons* you. The leading stripe is electric violet and pulses with a longer, more deliberate cadence than running. A small `▶ Decide` affordance sits at the bottom-right of the card. The card's surface tone is half a step warmer toward violet (`#171436`) so the whole card feels charged.
**Values**: Surface `#171436`. Stripe `#A78BFA`, pulse 1.4s cycle. Chip `WAITING ON YOU`, container `#2E1A5C`, text `#DDD0FF`. Decide affordance: `type/label-l`, color `#A78BFA`, with chevron.

#### State: `done`
**Visual Description**: Settled. Sage stripe, no halo, no pulse, no spinner. A small ✓ glyph (sage) accompanies the timestamp. Surface returns to default `surface/sub`. The card recedes — readable but quiet.
**Values**: Stripe `#7DCFA5`. Chip `DONE`, container `#143A29`, text `#B8E8CD`. Check glyph 14dp, `#7DCFA5`.

#### State: `error`
**Visual Description**: Coral stripe. The card carries a faint coral undertone in its surface (`#1C141A`). A short error reason is shown below the title in `type/body-m`, color `text/secondary`. A `Retry` text-button (coral) appears bottom-right.
**Values**: Surface `#1C141A`. Stripe `#FF6B6B`. Chip `ERROR`, container `#4A1818`, text `#FFC4C4`. Reason text 1 line, ellipsis. Retry button: `type/label-l`, color `#FF6B6B`.

### 7.3 Tool Call Card (Collapsed + Expanded)

**Visual Description (collapsed)**: A nested card inside the session detail's turn list. Reads as a *receipt* of an action the agent took. Tighter than a session card. Leading 3dp stripe in `accent/vela` cyan (signature: this is a tool action, not human content). A small tool-icon (16dp, monochrome) sits next to the tool name in monospace. To the right: the duration in `type/label-m` and a ▾ chevron. The whole card has a subtle 1dp inner stroke at `stroke/hairline` to differentiate from regular agent text turns.

**Values (collapsed)**:
- Container: `surface/raised` `#171C36`, `shape/card` 20dp radius, padding 14dp 16dp.
- Inner stroke: 1dp, `#FFFFFF` 6% alpha.
- Leading stripe: 3dp, `accent/vela` `#5EEAD4`.
- Tool icon: 16dp, `text/secondary`.
- Tool name: `type/mono-m` (JetBrains Mono 13sp), `text/primary`.
- Duration: `type/label-m`, `text/tertiary`, right-aligned.
- Chevron: 16dp, `text/tertiary`, rotates 180° on expand.

**Visual Description (expanded)**: The card *unfolds* with `motion/spring-expressive`. Reveals two stacked sub-blocks: **Args** (top) and **Result** (bottom), separated by a 1dp `stroke/hairline`. Each sub-block has a small uppercase eyebrow label (`ARGS`, `RESULT`) in `type/caption-uppercase`, `text/tertiary`. Content is rendered in `type/mono-m` with appropriate syntax color (keys in `accent/vela`, strings in `text/primary`, numbers in `status/running` amber, booleans in `status/waiting` violet). Long results are scrollable inside the card up to 320dp max-height. A copy-icon button (top-right of each sub-block) is available.

**Values (expanded)**:
- Surface bumps to `#1A1F3C` (one tonal step) on expand.
- Sub-block padding: 12dp.
- Eyebrow gap: 4dp below eyebrow.
- Args block max-height: 200dp (scroll within).
- Result block max-height: 320dp (scroll within).
- Syntax colors as above.
- Expand animation: height `motion/spring-expressive`, content opacity 0→1 starting at t=80ms over 200ms.

### 7.4 Voice Capture Overlay

**Visual Description**: Triggered by holding (or tapping) the Voice FAB. The screen darkens to 80% abyss. From the FAB's position, a giant circle blooms outward — a single, full-screen circle of `surface/peak` indigo with a thin `accent/vela` cyan ring at its edge. At the center: the user's transcript, rendered live in **Instrument Serif at 32sp**, animating word-by-word as speech is recognized. Around the perimeter, a soft audio-reactive ring of cyan particles or a waveform — a *halo* that responds to voice volume in real time. At the bottom: two large circular buttons — a coral Cancel (left, 56dp) and a cyan Send (right, 64dp, larger). No status bar, no nav bar. The world is the voice.

**Values**:
- Background: `surface/abyss` at 80% alpha overlaying current screen.
- Bloom circle: `surface/peak` `#1F2542`, full-screen circumscribing radius, edge stroke 1.5dp `#5EEAD4` at 60% alpha.
- Transcript: Instrument Serif **32sp**, weight 400, `text/primary`, centered, max 4 lines, fades older lines to `text/secondary`.
- Audio reactive ring: 280dp diameter centered behind transcript, cyan particles or smooth waveform driven by mic amplitude. Max excursion ±18dp from base radius.
- Cancel button: 56dp circle, `surface/raised`, coral icon `status/error`.
- Send button: 64dp circle, `accent/vela` `#5EEAD4` fill, `surface/abyss` icon, `motion/spring-snappy` press.
- Bloom transition: `motion/spring-voice`.
- Dismiss: swipe down from top, or tap outside transcript area, or Cancel.

### 7.5 Coordinator Branch Card

**Visual Description**: Used inside a Coordinator session's work-graph view. Each card represents one node's parallel branch of the work. The card has **asymmetric rounding** — fully rounded leading edge, tighter trailing edge — making it read as a "lane" issuing from the conductor. A 2dp connector line draws from the coordinator stem into the leading edge of the card (rendered at view-level, not inside the card). The card's interior shows: node-name (in monospace, since it's a machine identifier here, `type/mono-m`), the branch's current step description (`type/body-m`), and a mini-status pip in the top-right. Coordinator surface tone (cool teal-tinted) replaces the indigo for these cards only — they belong to the coordinator's visual subdomain.

**Values**:
- Container: `#13303C` (coordinator surface), `shape/branch` (24dp leading / 8dp trailing), padding 16dp.
- Inner stroke: 1dp, `#1FE0C2` at 12% alpha.
- Connector line: 2dp, `#1FE0C2` at 60% alpha, drawn from parent coordinator stem to card's vertical center on leading edge.
- Node name: `type/mono-m`, `text/primary`.
- Step description: `type/body-m`, `text/secondary`, max 2 lines.
- Mini-status pip: 10dp circle, status color, top-right.

### 7.6 Approval Gate Sheet

**Visual Description**: When a session pauses for human approval, a full-width bottom sheet rises with `motion/spring-expressive`. Top corners are 32dp rounded; the rest of the screen dims to 60% abyss. The sheet's surface is `surface/peak`. At the top: a 4dp drag handle. Below: a violet eyebrow `APPROVAL REQUIRED` (`type/caption-uppercase`, `status/waiting` color). Below that: a serif title in `type/display-m` describing what's being asked — *"Run the migration on production?"*. Below: an optional context block (the agent's reasoning or the tool call about to fire), in `surface/raised` nested card with `type/mono-m`. At the bottom: two large buttons — **Deny** (text-button style, coral) on the left, **Approve** (filled, cyan) on the right, each min 56dp tall, fully rounded.

**Values**:
- Sheet container: `surface/peak` `#1F2542`, top corners 32dp.
- Backdrop scrim: `#000000` at 60% alpha.
- Padding: 24dp horizontal, 20dp top (after handle), 20dp bottom.
- Drag handle: 36×4dp, `text/tertiary`, 12dp from top.
- Eyebrow: `type/caption-uppercase`, color `#A78BFA`.
- Title: `type/display-m` (Instrument Serif 36sp), `text/primary`, 12dp below eyebrow.
- Context block: `surface/raised` card, 12dp padding, `type/mono-m`, max-height 280dp scrollable.
- Deny button: text-style, height 56dp, full-width 48% of row, color `#FF6B6B`, label `DENY` in `type/label-l`.
- Approve button: filled, height 56dp, full-width 48% of row, container `#5EEAD4`, label color `#0B0E1A`, label `APPROVE` in `type/label-l`.
- Inter-button gap: 12dp.

### 7.7 Persistent Voice FAB

**Visual Description**: The protagonist of the app. Present on **every screen** except the voice overlay itself. **Not a stock Material FAB.** It is a layered, sculptural object: an outer glow halo, a middle ring (1.5dp stroke), an inner solid disc, and a centered mic glyph. At rest (idle), the halo is faint cyan and the disc is `surface/peak`; the ring is `accent/vela`. When a session on the current node is running, everything shifts: the halo becomes amber (`status/running`), brighter and breathing; the ring becomes amber; the disc is amber too, so the FAB itself glows like a coal. When pressed, it anticipates (shrinks 4dp), then blooms into the voice overlay (see §7.4).

**Values**:
- Diameter: **64dp** (significantly larger than standard 56dp Material FAB).
- Position: bottom-right, 16dp from screen edges. On screens with bottom nav, sits 16dp above nav.
- **Idle state**:
  - Outer halo: `#5EEAD4` at 18% alpha, 28dp blur radius behind FAB.
  - Ring: 1.5dp stroke, `#5EEAD4`.
  - Disc: `surface/peak` `#1F2542`.
  - Mic glyph: 26dp, `#5EEAD4`.
- **Running state** (any session on current node is running):
  - Outer halo: `#F5A524` at 24% alpha, 32dp blur, **breathing** (see §6).
  - Ring: 1.5dp stroke, `#F5A524`.
  - Disc: `#F5A524`.
  - Mic glyph: 26dp, `#1A1000` (on-color).
- **Press feedback**: scale 1.00 → 0.94, `motion/spring-snappy`.
- **Activation**: hold-to-talk OR tap-to-toggle (user setting). On activation, transitions to overlay via `motion/spring-voice`.
- **Disabled state** (no node connected): halo gone, ring `text/disabled`, disc `surface/sub`, glyph `text/disabled`, no breathing.

---

## 8. Screen-by-Screen Design Intent

### Screen 1: Home — Connected Nodes

**Visual Description**: You open the app and see a vertical list of substantial node tiles, each one named in serif at headline scale. The home screen feels like a wall of named instruments — each tile is a node you control. Above the list: a slim app bar with the Vela wordmark (cyan, weight 600) on the left and a settings glyph on the right. The Voice FAB hovers bottom-right. There is no chat input, no "How can I help you today?" greeting — just your fleet, listed.

**Values**: App bar 56dp, transparent background. Wordmark `accent/vela` 18sp Inter weight 700. Node tiles per §7.1, 12dp inter-card spacing. List padding: 16dp horizontal, 16dp top, 96dp bottom (to clear FAB). Empty state: centered serif text "No nodes connected yet" + a `Connect a Node` button (cyan filled, 48dp height, 24dp radius).

### Screen 2: Node Detail (Projects List)

**Visual Description**: The node's name is rendered as a hero — `type/display-l` Instrument Serif 48sp — anchored at the top with generous breathing room. Below the name: telemetry meta (`online · 4 active sessions · last sync 12s ago`) in `type/body-m`. Below that: a section eyebrow `PROJECTS` in `type/caption-uppercase`. Then a list of projects as compact cards — `type/title-l` project name, `type/body-m` description, session count. The hero title is the moment that sells the design language.

**Values**: Hero block padding 24dp horizontal, 32dp top, 24dp bottom. Hero name `type/display-l`. Telemetry 12dp below name. Section eyebrow 32dp below telemetry. Project cards `surface/sub`, 16dp radius, 16dp padding, 12dp inter-card spacing. Back chevron in app bar (24dp, `text/primary`).

### Screen 3: Project Detail (Sessions List)

**Visual Description**: Two sections, stacked: **Active** (sessions currently running or waiting) and **Recent** (done/error). Active sessions get the running-state cards with their amber halos and pulses — the section is alive with motion. Recent sessions are quiet sage or coral-stripe cards, no halos. A `+ New session` filled button sits below the project title, full-width, `accent/vela` filled, 56dp height, 28dp radius — your primary action besides voice.

**Values**: Project title `type/headline-l`. Section eyebrows in `type/caption-uppercase`. Session cards per §7.2. New-session button: full-width minus 16dp gutters, `accent/vela` `#5EEAD4` fill, label `+ NEW SESSION` in `type/label-l` color `#0B0E1A`.

### Screen 4: Session Detail (Turn History)

**Visual Description**: The most "content-heavy" screen, but designed to remain calm. Title at top in `type/display-m` (the user's first prompt or a generated session name). Below, a vertical scroll of turns: alternating user prompts (right-aligned, no card background, just serif text with a subtle leading-edge cyan accent line) and agent turns (left-aligned, body sans-serif on `surface/sub` cards). Tool-call cards (per §7.3) appear inline within agent turns, distinct from prose. A live status pill at the very top under the title — `RUNNING ON node-7` in amber, or `WAITING ON YOU` in violet, etc. Voice FAB is contextual here: speaking sends a follow-up to the running session.

**Values**: Title `type/display-m`, padding 20dp horizontal, 24dp top, 12dp bottom. Status pill: `shape/chip`, status container color, 12dp below title. Turn list padding: 16dp horizontal, 16dp inter-turn spacing. User turns: serif `type/body-l` (16sp), `text/primary`, max-width 80%, right-aligned, with a 2dp leading cyan stripe. Agent turns: `surface/sub` card, 20dp radius, 16dp padding, sans `type/body-l`, full-width minus user-turn inset.

### Screen 5: Coordinator Session Detail

**Visual Description**: The work graph. The screen tone shifts to the coordinator's cooler teal-tinted indigo — you should feel the temperature change when you enter this view. At the top: the coordinator's name in `type/display-m`, with a subtle cyan-teal gradient applied to the title text only (this is the *one* place a gradient is permitted in the entire app). Below: a vertical "river" of branches — a central conductor stem on the left, with branch cards (per §7.5) flowing out to the right at the moments they were spawned. Each branch's progress is visible as its status stripe; converging branches re-join the stem with a 2dp teal line. A small minimap-style scrubber at the right edge lets you scroll through long graphs.

**Values**: Background `#0C1E26`. Title gradient: `#5EEAD4` → `#1FE0C2`, applied to title text only. Conductor stem: 2dp wide, `#1FE0C2` at 60% alpha, runs vertically from title to bottom of content, indented 32dp from leading edge. Branches connect with curved 2dp lines (24dp curve radius). Inter-branch spacing 16dp vertical.

### Screen 6: Voice Capture (Full-Screen Overlay)

See §7.4. Full overlay, no other chrome.

### Screen 7: Approval Gate

See §7.6. Bottom sheet, dimmed backdrop.

### Screen 8: Node Configuration / Push Bundle

**Visual Description**: A focused "control panel" feel. Top: node name in `type/headline-l`. Below: a sectioned form — **Bundle** (which capability bundle to push), **Tools** (toggleable list), **Secrets** (masked field set), **Limits** (sliders for resource caps). Each section is a `surface/sub` card with internal section title, padding 20dp. Toggles use M3 Expressive switches (large, with the active state in `accent/vela`). Sliders have visible value chips above the thumb. At the bottom: a sticky `PUSH TO NODE` filled cyan button (full-width minus 16dp gutters, 56dp tall, 28dp radius). When push is in progress, the button transforms in place into a progress indicator (linear progress bar inside the same shape).

**Values**: Section cards `shape/card` 20dp radius. Switches: track 52×32dp, thumb 24dp, active track `#5EEAD4`, active thumb `#0B0E1A`, inactive track `#1F2542`, inactive thumb `#7A7E94`. Sliders: track 4dp, thumb 20dp circle in `accent/vela`. Value chip above thumb: `shape/chip`, `surface/peak`, `type/label-m`.

### Screen 9: Connect a Node (Onboarding / Add Flow)

**Visual Description**: Calm, focused. Title in `type/display-m`: "Connect a node." Below, a single primary input field for the node address, large — 56dp tall, 16dp radius, `surface/raised` fill, with the `accent/vela` border appearing on focus (1dp → 1.5dp animation, `motion/spring-snappy`). Helper text below in `type/body-m text/secondary`. Below: a `Pair via QR` text button. At the bottom: a filled `CONNECT` button (cyan, full-width minus gutters, 56dp). On successful connection, the screen pops back to home with the new node card animating in from above with `motion/spring-expressive`.

**Values**: Input field height 56dp, radius 16dp, `surface/raised` background, focused border 1.5dp `#5EEAD4`. Helper text 8dp below input. Pair via QR: text button, `accent/vela`, 16dp below input. Connect button: as Approve button in §7.6.

---

## 9. Explicit Anti-Patterns

This design **must not** exhibit any of the following. These are named, specific AI-slop tropes Vela rejects.

### 9.1 The "ChatGPT Wrapper" Look
**Forbidden**: Centered logo + "How can I help you today?" greeting. Suggestion chips below an input. A bottom-anchored chat composer as the primary surface. **Vela has no greeting and no chat composer on home.** The Voice FAB is the input. The home screen lists nodes, period.

### 9.2 Generic Dark-Mode Productivity (2021)
**Forbidden**: `#0F0F0F` or `#1A1A1A` flat-black backgrounds. Pure-grey cards. Blue-only accent palette. Tiny 8dp rounded rectangles. Material 2 drop shadows under everything. **Vela's dark is indigo (`#0B0E1A`) with character; cards are tonal indigo, not grey; shapes are bold (20–28dp); shadows are forbidden except the FAB glow.**

### 9.3 "Futuristic AI" Neon-on-Black Cyberpunk
**Forbidden**: Hot pink + lime green + electric blue gradients. Glitch effects. Monospaced display titles "for the techy feel." Animated wireframe meshes. **Vela uses one signature accent (`#5EEAD4`) with disciplined semantic colors; serif (not mono) for display; no glitch, no mesh, no synthwave.**

### 9.4 The "AI Personality" Avatar
**Forbidden**: A round friendly face avatar for the assistant. A name like "Vela AI" with a smiley. Speech bubbles. "Vela is typing..." indicators. Animated mascot. **The AI is the crew, not a character. Tool-call cards and turn cards do not have personality avatars. There is no chat bubble shape anywhere.**

### 9.5 Pastel Productivity / "Linear-clone" Minimalism
**Forbidden**: Pastel mint-and-cream surfaces. Hairline 1dp grey borders on everything. Tiny 12sp body text "for density." Extreme greyscale with one tiny purple accent. **Vela has weight, color, and serif drama. Density is comfortable, not extreme. Borders are absent by default.**

### 9.6 Decorative Color Without Semantic Weight
**Forbidden**: Random gradients on cards. A "purple section" and a "blue section" because variety. Status colors used as branding. **In Vela, color is load-bearing: amber means running, violet means waiting, sage means done, coral means error. These four colors never appear except in those roles.**

### 9.7 Linear / Mechanical Motion
**Forbidden**: 200ms linear ease-in-out fades. CSS-default cubic-bezier `(0,0,1,1)` everywhere. Snap transitions with no physics. **All transitions in Vela use spring physics. The single exception is the running-state spinner glyph, which is mechanical by design.**

### 9.8 Stock Material Components Used As-Is
**Forbidden**: Default 56dp Material FAB with a stock mic icon. Default M3 chip styles with no thought. Default switch sizes. Stock M3 Card with no surface-tone discipline. **Vela's components are M3 Expressive *interpretations*, not defaults. The Voice FAB is 64dp with layered chrome. Cards use disciplined surface tones, not default elevation overlays.**

### 9.9 Status Communicated Only by Text or Icon
**Forbidden**: "Running" written in body text with a tiny grey clock icon. **Status in Vela is communicated by color, by surface tone shift, by halo, by motion (pulse/breathe), and only secondarily by chip text. A user should know a session is running from across the room without reading.**

### 9.10 Glassmorphism / Frosted Blur Aesthetics
**Forbidden**: Translucent blurred surfaces. iOS-style backdrop-filter blurs. "Glass" cards. **Vela's surfaces are solid tonal indigo. The only blur in the system is the Voice FAB glow halo, which is a soft radial light, not a frosted-glass effect.**

### 9.11 Centered Hero Illustrations
**Forbidden**: Empty states with cute centered illustrations of robots, rockets, or people-with-laptops. **Vela's empty states are typographic and minimal — a serif sentence and a button. No illustrations.**

### 9.12 The "Just Use Blue" Design
**Forbidden**: Blue as primary, blue as accent, blue links, blue buttons, blue progress bars. **Blue is conspicuously absent from Vela's accent system. The signature is cyan-aqua. The semantic palette is amber/violet/sage/coral. If you find yourself reaching for `#3B82F6`, stop.**

### 9.13 Cluttered Bottom Navigation
**Forbidden**: A 5-tab bottom nav with icons and labels and a center FAB. **Vela has no bottom nav. Navigation is hierarchical (Home → Node → Project → Session) with back gestures. The Voice FAB is the only persistent bottom-anchored element.**

### 9.14 Performative Typography
**Forbidden**: Rendering body text in an italic serif "for elegance." Display text in a thin weight that's unreadable. Mixing 4+ font families. **Vela uses serif at display/headline sizes only; body and UI are Inter; tool I/O is JetBrains Mono. Three families, deliberate roles.**

### 9.15 Dark Mode With Pure White Text
**Forbidden**: `#FFFFFF` body text on dark. Surgical, harsh, fatiguing. **Vela's `text/primary` is `#F5F2EC` — a near-white with a faint warm cast that holds up against indigo.**

---

## Closing Note for the Generator

When in doubt, ask: *does this feel like a cockpit?* If the answer is "this feels like a chat app," reject the decision. If the answer is "this feels like a 2021 productivity tool," reject the decision. If the answer is "this feels like a calm, instrumented surface for directing distributed AI compute, with serif gravitas, semantic color, spring physics, and a single glowing voice button as its protagonist" — you are rendering Vela.
