status: must-watch
issues: #1135

- **Name the money (#1135 PR 1):** Money tab → the "what you earned" bar has four segments (base pay /
  tips / not itemized / not matched) and NO "Bonuses & other"; its legend dollars, minus any "gig.log recorded … more than
  was reported" line beneath it, sum to the Earned figure above it to the cent; a stacked job's un-split receipt shows under "not itemized" with "estimated from
  the offer" named when the row was priced from the offer; a dash whose summary exceeded its deliveries
  shows "reported for a dash but not matched to a recorded delivery". Dash detail → the big figure is
  labelled "Reported — dash summary" / "— in-dash counter" / "— entered by you" / "Recorded by gig.log
  (no report)". Export → `sessions.csv` has a `report_source` column. — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): not exercised. Counter unchanged.
  Why proposed: Money reconciliation: the labels, segment totals and report provenance must agree to the cent.
  Triage source: first-line blame 2026-10-05 (`e1d50318c`, original README L51).
