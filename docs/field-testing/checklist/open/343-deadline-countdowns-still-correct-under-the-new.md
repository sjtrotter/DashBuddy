status: review
issues: #343

- **Deadline countdowns still correct under the new transform clock (#343).** Time parsing
  (`parseDeadline`/`parseTime`) is now anchored to the observation's instant instead of the
  wall clock at evaluation time (replay determinism; the 05-19 "1434:38 ghost countdown" class).
  Live behavior should be identical — confirm pickup/dropoff "by HH:MM" countdowns on the cards
  match DoorDash's stated times, and no absurd ~24h countdowns appear (especially around
  midnight or just-past deadlines).
  - Confirmed: 0/2.
  desk: needs field: compare live pickup/dropoff countdowns with platform deadlines, including the day-boundary case.
  Triage source: first-line blame 2026-06-11 (`bab3f36e4`, original README L3071).
