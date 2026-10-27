status: review
issues: #342, #1054

- **Paused dash auto-expires when the pause clock runs out (#342).** The pause-safety timer
  used to fire into a void (routed to no platform region) — now it reaches the paused region.
  If you pause mid-dash and deliberately DON'T resume: once the pause duration lapses, the HUD
  should flip out of PAUSED on its own (offline with grace) without needing a DoorDash screen
  change. Also regression-watch the normal path: resuming before expiry must NOT flash offline
  (the timer is cancelled on resume).
  - Confirmed: 0/2.
  desk: needs field: allow a real pause to expire and compare the early-resume path; include #1054's newer restart/safety-timer behavior.
  Triage source: first-line blame 2026-06-11 (`950cef6ff`, original README L3056).
