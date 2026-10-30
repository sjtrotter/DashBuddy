status: review
issues: #3, #286, #290, #2

- **App-switch mid-dash → "Session resumed (grace)" → dash + task continuity
  (2026-06-03 #3).** The bubble's `"Session resumed (grace)"` message
  (`EffectMap.kt:319`) fires when a region goes Offline then back Online within
  ~10s on the **same** session. An app-switch return can trip this (gig.log
  stops seeing DoorDash → reads Offline → resumes on return). When it appears,
  confirm gig.log **kept the same in-progress dash AND the active task** with
  earnings intact — it must **not** start a fresh dash, double-start, or forget
  the task (cross-refs #286/#290 grace and 2026-05-29 #2). Also a UX read: is
  showing this internal-sounding message useful, or should it be reworded/demoted?
  - Confirmed: 0/2.
  desk: needs field: app-switch through the grace window and verify the same session and active task survive.
  Triage source: first-line blame 2026-06-03 (`1edea490a`, original README L2944).
