status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #1152, #251, #826
confirmed: 0/2

- [active] **🆕 NEW — Uber offers drawn as an overlay are captured, whatever has focus (#1152).** Uber draws its
  offer as a floating system overlay above other apps; it used to be read only when it had focus.
  Now a large system-layer window from an ENABLED overlay platform is a candidate (size ≥ ¼ of the
  screen, package verified). **Needs Uber enabled and an Uber offer arriving while DoorDash (or
  another app) is on screen.** **How to tell it works:**
  1. An `uber.screen.offer` frame is recognized whether or not the bubble is active and whether or
     not the overlay has focus — look for `overlaySnapshots=` RISING in the `PipelineStats` summary
     (it stays 0 when the overlay had focus: that frame comes through the active-root path).
  2. The small Uber puck is NEVER the frame — `overlayRejected{TOO_SMALL=…}` counts it, and no
     UNKNOWN capture shows the puck's tree.
  3. No DoorDash toast is ever the frame (no DoorDash `TYPE_SYSTEM` capture);
     `overlayRejected{NOT_OVERLAY_PLATFORM=…}` may rise when the notification shade is pulled down.
  4. While an Uber offer is up over DoorDash, DoorDash frames PAUSE — expected (the overlay on top is
     the frame). The app follows Uber while the card is up: anything that happens in DoorDash during
     those seconds is not seen, and a DoorDash pay reading that was still settling is dropped. Once
     the offer is dismissed, DoorDash frames resume and the state should catch up — note any DoorDash
     step that never shows up. (Seeing two platforms at once is the known multiplatform gap, #251 /
     #826.) With Uber DISABLED, an Uber offer must NOT pause DoorDash frames at all.
  5. `overlayScans=` in the summary shows how often the window list is read for this feature.
  - Issue: #1152. Confirmed: 0/2
  Triage source: first-line blame 2026-09-30 (`45b08c27e`, original README L279).
