status: must-watch
issues: #1134

- **Session report correction (#1134):** on session 483 (09-10 17:11, `early_offline`, 0 deliveries)
  tap Correct total → No summary; the Sep 7–13 week's Earned drops from $646.34 to $606.20 and the
  dash shows 'report corrected'; Restore detected value brings $40.14 back. — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): not exercised. Counter unchanged.
  Why proposed: Money correction: removing and restoring a false session report directly changes weekly earnings.
  Triage source: first-line blame 2026-10-05 (`30a3624a0`, original README L78).
