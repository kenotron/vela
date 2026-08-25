# Android Auto: One-Time First-Connect Provisioning (#52)

## Summary

The first time an Android phone connects to a given Android Auto head unit
(or, for phone-screen "Android Auto for phones" / Google Assistant driving
mode, the first time on that device), Google requires a human to be
physically present at the phone's screen to complete a one-time sign-in and
permission-grant flow. **This is enforced by Google/Android Auto itself, at
the OS and app level — it is not something Vela can automate away, script
around, or bypass.** It must be documented and accepted as a known manual
step, not engineered around.

This applies per physical head unit (each car/unit the phone pairs with for
the first time triggers its own first-connect flow) and is a one-time event
per device pairing — subsequent connections to the same head unit do not
re-trigger it under normal conditions.

## What the one-time step looks like

Based on Android Auto's publicly documented first-run behavior:

1. **Initial connection**: The phone is connected to the head unit (via USB
   or wireless Android Auto) for the first time. Android Auto detects a new,
   unrecognized head unit pairing.
2. **On-phone confirmation prompt**: Android Auto displays a prompt on the
   phone screen asking the user to confirm they want to use Android Auto
   with this vehicle/head unit. The driver (or passenger, before driving)
   must interact with this prompt on the phone — it is not surfaced on the
   head unit display until confirmed on the phone.
3. **Permission grants**: Android Auto requests the runtime permissions it
   needs on first run (commonly: phone/contacts access for calls, location,
   microphone for voice input, notification access for notification
   passthrough). Each of these follows the standard Android permission
   dialog pattern and requires a tap on the phone to grant.
4. **Google account / sign-in check**: If the phone is not signed into a
   Google account, or Android Auto requires reconfirming terms of service
   for that account, a sign-in / ToS acceptance screen is shown. This is
   the "Play/ToS sign-in flow" referenced in the issue — a human must
   accept it on the phone's screen.
5. **Session handoff**: Once confirmed, Android Auto's UI takes over the
   head unit display (or, for phone-screen mode, becomes the active driving
   surface) and subsequent app behavior (including Vela's) proceeds
   normally.

**Uncertainty / not independently verified**: The exact sequence, wording,
and number of permission dialogs can vary by Android version, Android Auto
app version, and head unit manufacturer. We have **no physical Android Auto
head unit available in this environment** to verify the flow firsthand. The
description above reflects Google's publicly documented first-run behavior
for Android Auto; it is not independently confirmed against real hardware
here. Where Vela testing later encounters a head unit, this document should
be updated with the actually-observed sequence.

## What this means for Vela

- **No engineering workaround exists or should be attempted.** Do not build
  auto-acceptance, prompt injection, or accessibility-service tricks to
  dismiss this flow programmatically — it is a Google-enforced human-presence
  gate (partly for driver-distraction/consent reasons), and attempting to
  bypass it would likely violate Android Auto's terms and could break with
  any Android Auto update.
- **Document and accept**: treat this as a known, one-time setup cost per
  device/head-unit pairing, communicated to users/testers as: *"The first
  time you connect to a new head unit, be ready to look at your phone and
  tap through a couple of confirmation screens before Android Auto (and
  Vela) becomes usable in the car."*
- **Testing implication**: any real-device testing of Vela's Android Auto
  integration must budget for this manual step being completed once per
  test head unit, by a human, before automated or scripted testing of the
  in-car experience can begin.

## Relationship to the voice-first Auto flow (#50/#51)

This first-connect provisioning step is a **precondition** for testing the
voice-first Android Auto flow tracked in #50/#51 (in flight as a sibling
lane). Anyone picking up #50/#51 for real-device testing needs to have
already completed this one-time manual sign-in/permission flow on the test
device against the target head unit; otherwise Android Auto itself won't be
active yet for Vela's voice flow to be exercised against.

## References

- Google's official Android Auto documentation on connecting and permissions
  (see Android Auto Help Center / developer docs for the current, canonical
  description of the first-run experience — not reproduced verbatim here
  since exact UI copy changes across releases).
- Issue #52 (this document).
- Issue #50/#51 (voice-first Auto flow — depends on this precondition).
