# #579 — Voice Accept/Decline: Platform Feasibility Report

**Date:** 2026-06-25 (committed 2026-07-01) · **Device target:** Pixel 7, Android 15/16 (minSdk 35, targetSdk 36)
**Provenance:** multi-agent research workflow — 5 web-research angles (2023–2026 official Android sources), 3 codebase-grounding reads, and a 3-lens adversarial verification pass over the make-or-break claims (45 verdict lenses completed; every crux claim below carries its verdict). Follow-up light research on the bubble-expansion path, plus two developer field corrections, are folded in. Raw structured corpus lives in the session workflow journal.

---

## 0. The requirement (fixed by the developer)

**Voice must be completely hands-free per offer, or it isn't worth building.** A "tap 🎙 then speak"
flow is strictly worse than just tapping Accept/Decline — any design that costs a tap *per offer*
is rejected. A one-time interaction at **dash start** is acceptable (the dasher is in the app
anyway); anything per-offer is not.

## 1. Bottom line

Completely hands-free voice accept/decline **is buildable, fully on-device**, with one design:
**arm a `microphone` foreground service once per dash, while DashBuddy is foreground at dash
start; it legally persists for the whole dash, and every subsequent offer is zero-tap** — the
recognizer listens after the offer-read TTS finishes, and a recognized "accept"/"decline"
dispatches through the existing verified-click path. No synthetic gestures, no notification-tap
trigger, no new actuation surface.

Two things are **not** paths to hands-free, despite being attractive:
- **A visible overlay window does not grant microphone access** (adversarially refuted 3/3 lenses —
  see §2). DashBuddy's bubble is not an overlay: **collapsed** it grants nothing; **expanded** it
  *is* a foreground Activity and does grant mic (§2 field corrections) — but expansion costs a
  per-offer interaction, so it fails the §0 bar as a primary design.
- **The AccessibilityService does not grant microphone access or mic-FGS standing** (confirmed;
  its only audio privilege is concurrent-capture *priority*, not the right to start capture).

The only *more* hands-free option is becoming the device's **VoiceInteractionService** (default
assistant) — heavyweight but real, and acceptable as a fallback on a single-developer device.

## 2. The platform constraint model (verified)

Android draws one distinction that decides everything. There are **two separate exemption
lists**, and conflating them is the classic error (one of our own research angles made it, and the
adversarial pass caught it):

1. **General FGS background-start exemptions** — *may a backgrounded app start a foreground
   service at all?* A visible `SYSTEM_ALERT_WINDOW` overlay **is** on this list (for apps
   *targeting* API 35+, the overlay must be *currently visible* — a targetSdk-gated change).
2. **While-in-use (WIU) exemptions** — *may that service access mic/camera/location?* This list is
   **stricter**, and the overlay is **not** on it. Verbatim from the FGS docs: an app needing
   while-in-use permissions *"cannot create the service while the app is in the background, even
   if the app falls into one of the exemptions from background start restrictions."*

Crux claims and their adversarial verdicts:

| Claim | Verdict |
|---|---|
| Visible overlay (SAW / `TYPE_APPLICATION_OVERLAY` / `TYPE_ACCESSIBILITY_OVERLAY`) grants WIU mic | **REFUTED 3/3** — WIU mic keys on `PROCESS_CAPABILITY_FOREGROUND_MICROPHONE` (TOP process, visible *Activity*, or an already-legally-started mic FGS), never on having a visible window |
| A11y service grants special mic access / mic-FGS standing | **REFUTED as "special access"** — only concurrent-capture priority (won't be silenced; "if the service's UI is on top, both the service and the app receive audio input"); `CAPTURE_AUDIO_HOTWORD` is `signature\|system` |
| "Service starts by interacting with a notification" exempts BOTH lists | **UPHELD** — a user tap on a notification action legally starts a mic FGS from background (rejected here only because it's per-offer) |
| Backgrounded app cannot *keep* capturing after it backgrounds | **REFUTED** — a mic FGS **started while foreground keeps capturing after the app backgrounds**; that is the documented purpose of the `microphone` FGS type ("continue microphone capture from the background, such as voice recorders or communication apps") |
| TTS holding audio focus blocks mic capture | **REFUTED** — audio focus governs *playback*; mic input is an independent arbitration policy (API 29+). The reason to wait for TTS `onDone()` is **self-recognition**, not a focus block |
| On-device `SpeechRecognizer` (API 31+) is offline-capable and needs no FGS of its own | **CONFIRMED with nuance** — Pixel = Google SODA, fully offline once the language pack is downloaded; needs `RECORD_AUDIO`; recognition runs in Google's `RecognitionService` but the **caller's** WIU state governs the mic; `EXTRA_PREFER_OFFLINE` belongs to the intent path, not this factory |

Mechanics on API 34+: a `microphone` FGS requires `android:foregroundServiceType="microphone"`,
the `FOREGROUND_SERVICE_MICROPHONE` + `FOREGROUND_SERVICE` manifest permissions, and a granted
`RECORD_AUDIO` at `startForeground()` time (else `SecurityException`). For apps targeting API 35+
the SAW general-start exemption narrowed to visible-overlay-only and the BOOT_COMPLETED ban
extended to more FGS types; API 36 adds no new mic/FGS restrictions. Android 17's "background audio hardening" targets playback/focus, not
mic capture — but it's a reason never to rely on an FGS *started from* the background for audio.

### Field corrections (developer, 2026-06-25)

- **Expanded bubble ≠ broken clicks.** The #457 window-displacement failure is the *notification
  shade* only. With the bubble **expanded**, the in-bubble Accept/Decline verified clicks work
  fine (field-confirmed). Earlier drafts' "collapse before tapping" sequencing is retracted.
- DashBuddy's bubble is a **system Bubble** (`BubbleMetadata` + `BubbleActivity`), *not* a
  SYSTEM_ALERT_WINDOW overlay. When expanded it is a **real foreground Activity** — the docs are
  explicit: *"the content activity goes through the normal process lifecycle, resulting in the
  application becoming a foreground process."* An expanded bubble therefore grants WIU mic with
  **no FGS at all** — relevant to fallbacks below.

## 3. Options, ranked against the hands-free bar

1. **Arm-once-per-dash mic FGS — the v1 direction.** Dasher toggles "hands-free voice" at dash
   start (DashBuddy foreground → mic FGS starts legally) → FGS persists all dash → every offer:
   TTS reads the offer → recognizer arms on `onDone()` → "accept"/"decline" → existing dispatch.
   Zero per-offer interaction. Costs: green mic indicator while listening (keep the FGS alive but
   spin the recognizer up only during offer windows), battery, first `RECORD_AUDIO` + first FGS in
   the app.
2. **VoiceInteractionService (default assistant)** — true zero-*touch* including no dash-start
   toggle; grants background mic + hotword by design. Displaces Google Assistant; a system role,
   not a per-offer mechanism. Acceptable single-device fallback; large scope.
3. **Bubble auto-expand** (a11y `dispatchGesture` taps our own collapsed chat-head → expanded
   `BubbleActivity` = foreground → mic, no FGS): destination is doc-solid, but the *trigger* is
   unproven — `setAutoExpandBubble`/re-notify only honor auto-expand when already foreground; an
   a11y service has **no** background-activity-launch exemption, so expansion must go through the
   (synthetic, coordinate-fragile) tap on a draggable SystemUI target. Fallback only, if FGS
   persistence fails.
4. **Notification "🎙 Speak" action** — documented and buildable, **rejected**: per-offer tap.
5. **Wake word ("Hey DashBuddy")** — the low-power DSP/hotword path (SoundTrigger/
   `AlwaysOnHotwordDetector`, `CAPTURE_AUDIO_HOTWORD`) is **privileged/system-only**. A software
   wake-word engine (e.g. Porcupine/Vosk keyword spotting) *could* run on option 1's armed FGS,
   but costs continuous capture (permanent mic indicator, battery) and adds nothing over
   option 1's offer-window listening.
6. **Auto-listen on offer arrival from background (no FGS, no role)** — **blocked**: posting a
   notification is not an "interaction"; there is no grace window.

## 4. The go/no-go device spike (run before any feature code)

On the Pixel 7 (API 35/36):
1. Start a `microphone` FGS while DashBuddy is foreground (dash-start simulation).
2. Background DashBuddy; bring DoorDash foreground; drive a real dash session length.
3. Periodically run `createOnDeviceSpeechRecognizer` sessions and confirm **non-silent audio**
   (no `SecurityException`, no all-zero frames) hours in — i.e. the FGS retains live mic capture
   across the dash.
4. Confirm no OEM/battery-optimization reap (watch for FGS death in logs) and no runtime cap on
   the `microphone` FGS type.
5. Note green-dot behavior when the recognizer is idle vs listening.

Pass → build v1. Fail → fall back to option 2 or 3.

## 5. DashBuddy integration plan (grounded)

- **Config:** `voiceControlEnabled` on `OfferAutomationConfig` (default OFF), mirroring #577's
  `quickDeclinesEnabled` exactly: `StrategyDataSource.Keys` boolean + flow + setter
  (`core/datastore/.../strategy/StrategyDataSource.kt:28-71,146-162`), `StrategyRepository`
  combine + delegate (`core/data/.../strategy/StrategyRepository.kt:58-78,125`).
- **FGS lifecycle:** started from the dash-start surface (foreground moment); stopped at dash end
  (`DASH_STOP` effect). Keep alive across the dash; recognizer sessions only during offer windows.
- **Arming:** `TtsEffectHandler` already implements `UtteranceProgressListener`; `onDone()`
  (`app/.../state/effects/TtsEffectHandler.kt:44`) releases audio focus — the natural "arm the
  recognizer" hook (avoids self-recognition). New `AppEffect.ArmVoiceRecognizer` +
  `VoiceRecognitionEffectHandler` following the `OdometerEffectHandler` pattern.
- **Recognizer:** `isOnDeviceRecognitionAvailable` → `checkRecognitionSupport` →
  `triggerModelDownload` (first-run) → `createOnDeviceSpeechRecognizer` + `RecognitionListener`;
  tiny command grammar; request `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` during listening. Fallback
  engine if SODA proves unreliable: Vosk (Apache-2.0, ~50 MB, streaming).
- **Dispatch (no new actuation, but an explicit consent fork):** recognized command →
  `Observation.UiInput(action=accept_offer/decline_offer)` (`domain/.../pipeline/
  Observation.kt:127-133`) → `EffectMap.diffFlowRegion()` → `PerformRuleAction` →
  `UiInteractionHandler.performVerifiedClick`. **Design decision the button path does not answer:**
  the notification/bubble buttons emit `trigger=USER` (`EffectMap.kt:267` — the press *is* the
  consent, #417 gate bypassed), while the only `AUTOMATION` emission today is the #577 SETTLE_UI
  timeout (`EffectMap.kt:947`), not a `UiInput`. A machine-recognized spoken command in a noisy
  car is **not** equivalent to a physical press — voice should dispatch as **`AUTOMATION`** (a
  flagged `UiInput` or a distinct observation that `diffFlowRegion` maps to
  `trigger=AUTOMATION`), so it passes the #417 capability gate; this deliberately diverges from
  the button path's USER trigger.
- **Safety:** ACCEPT commits money in a noisy car — v1 is **decline-only**, or accept requires a
  spoken confirmation. All processing on-device (pledge-consistent); the recognizer must never run
  while the dasher's own TTS is speaking.

## 6. Open questions

- FGS mic persistence across hours (the spike, §4).
- Recognizer contention/error behavior if DoorDash or a call ever holds the mic
  (`ERROR_RECOGNIZER_BUSY` handling → fall back to tap).
- Whether keeping the FGS alive but recognizer-idle suppresses the green indicator between offers.
- First-run UX of the SODA language-pack download.

## 7. Key sources

- Foreground service types (`microphone` requirements, WIU restriction): developer.android.com/develop/background-work/services/fg-service-types
- FGS background-start restrictions + **both exemption lists** (verbatim WIU carve-out): developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- Sharing audio input (concurrent-capture priority, a11y rule, privacy-sensitive capture): developer.android.com/media/platform/sharing-audio-input
- Bubbles (expanded bubble = foreground process; auto-expand semantics): developer.android.com/develop/ui/compose/notifications/bubbles
- Background activity launch restrictions (no a11y exemption): developer.android.com/guide/components/activities/background-starts
- `SpeechRecognizer` / on-device recognition (API 31+; `checkRecognitionSupport`, `triggerModelDownload`): developer.android.com/reference/android/speech/SpeechRecognizer
- Android 15 behavior changes (SAW visible-overlay narrowing, BOOT_COMPLETED extensions): developer.android.com/about/versions/15/behavior-changes-15
- Android 17 background audio hardening (playback-only): developer.android.com/about/versions/17/changes/bg-audio
