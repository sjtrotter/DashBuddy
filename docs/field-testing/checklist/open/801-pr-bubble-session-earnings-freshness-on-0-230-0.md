status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #801, #817
confirmed: 0/2

- [review] **🆕 NEW — #801 / PR #817 — bubble session-earnings freshness on 0.230.0.** The collapsed receipt
  no longer refreshes session `runningEarnings` (the 0.230.0 digit-wheel is unparseable); the figure now
  rides the dash-control "This dash" label parse alone. After a delivery on 0.230.0, verify the bubble's
  session-earnings figure still updates post-receipt; watch for a stale figure. Desk-side:
  `sessionEarnings=null` on collapsed parses is EXPECTED on 0.230.0; the dash-control parse lines are the
  live source.
  - Confirmed: 0/2 (desk 07-26: the `sessionEarnings=` grep has NO corresponding log site in current
    code — desk hint is dead, a no-hit proves nothing. Indirect evidence only: `[Earnings]: Saved:
    $12.90` + an exact session reconciliation post-install. Needs dev eyes on the bubble figure.)
  desk: needs field: watch post-receipt bubble earnings on the specified platform UI; parsed running totals alone cannot prove visible freshness.
  Triage source: first-line blame 2026-07-20 (`31a7ab9b7`, original README L1409).
