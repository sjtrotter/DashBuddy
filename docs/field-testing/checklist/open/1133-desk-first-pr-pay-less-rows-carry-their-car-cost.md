status: active
issues: #1133, #1219

- **Desk-first — #1133 (PR #1219): pay-less rows carry their car cost.** After the first launch on this
  build (projector v12 refold), the hub's "where your money went" card should show the gas/wear split
  for a week whose drops all carry a split (the guard no longer trips falsely), "kept" moves down by the
  receipt-less drops' mileage cost, and a receipt-less drop in a session drill-down reads pay `—` with
  the caption "pay not recorded — car cost only" above a negative net. Report a drop showing a negative
  net WITHOUT that caption, or a `$0.00` net on a drop that drove no miles.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`a9959d413`, original README L120).
