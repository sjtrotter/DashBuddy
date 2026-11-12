status: active
issues: #1459

- **An early-ended shift never reports less than its recorded pay (#1459):** end a shift without reaching
  DoorDash's summary screen (go offline early, or the app is closed before the summary) after a delivery that
  DoorDash's running total had not caught up with. After the next pull, open that shift's
  detail screen: its figure is the recorded pay, labelled "Recorded pay" with "Above running total" under it, not the
  lower running total, and the bubble's resting figure is the same number (it no longer drops below the last
  live figure). A shift that ended on the summary screen keeps the summary's total and its "From the shift
  summary" label, even if lower; a shift whose running total equals its recorded pay keeps "From the app's
  running total". In `sessions.csv` the shift's `report_source` is `RECORDED_PAY` and `summary.csv` lists it
  under `total_recorded_pay_earnings`. — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): not exercised. Counter unchanged.
