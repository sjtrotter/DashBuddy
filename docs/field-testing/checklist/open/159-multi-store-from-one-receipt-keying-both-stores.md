status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / analytics
issues: #159, #739, #315
confirmed: 0/2

- [review] **🆕 NEW — multi-store-from-one-receipt keying: both stores keyed from a single end-of-job receipt, no downgrade on payout-less close (#159 / PR #739).**
  Narrowed scope: the basic-keying half retired 2/2 (07-17 + 07-18/19 — all deliveries carry real running
  keys, no more empty `…|target|` segments). What's left is the harder multi-store case. **How to tell it's
  working (desk-side, after a multi-store stack — e.g. the Target+Maple case):** BOTH stores should be
  keyed from the single end-of-job receipt, not one keyed + one chain-only. And a payout-less close
  (`DASH_STOP` with no summary) must NOT downgrade an already-keyed store back to chain-only. No UI yet
  (the #315 Patterns tab is the consumer) — verify via the DB / a CSV-adjacent read.
  - Confirmed: 0/2 (multi-store-from-one-receipt case still unfielded)
  desk: desk-verifiable: join a multi-store receipt to both stored place keys and verify a payout-less close does not downgrade either key.
  Triage source: first-line blame 2026-07-19 (`c553384d9`, original README L1641).
