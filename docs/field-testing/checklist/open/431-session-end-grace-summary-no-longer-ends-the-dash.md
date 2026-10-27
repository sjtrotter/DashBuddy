status: active
issues: #431

- **Session-end grace — summary no longer ends the dash instantly (#431).**
  The dash-summary screen now arms a ~2.5s authoritative grace (cancellable by a task-flow
  frame) instead of ending the session on the spot, and grace commits fire on a timer instead
  of waiting for the next event. Watch: (a) the post-dash summary still attributes to the
  right session (chat/cards/totals) — just ~2.5s later; (b) NO spurious mid-dash session
  splits (the old failure was one misrecognized frame = split); (c) leaving the app right
  after going offline still logs DASH_STOP promptly (timer-driven) with endedAt ≈ when you
  went offline. Broken = duplicate DASH_START/STOP pairs, summary attributed to a new empty
  session, or a session lingering long after the dash.
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** dash ended cleanly — summary on the right session, no
    spurious mid-dash splits, no lingering session (supports (a)/(b); (c) leave-app-after-offline not
    explicitly checked). One more to validate.
  Triage source: first-line blame 2026-06-12 (`298718b52`, original README L2727). Recorded first confirmation: 2026-06-14 (partial) (existing evidence only).
