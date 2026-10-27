status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #860, #871, #1126, #1213
confirmed: 0/2

- [review] **🆕 NEW — #860 / PR #871 — Building Name value masked on both dropoff workflow sheets.**
  **Desk only:** any `dropoff_pre_arrival` / `dropoff_pre_arrival_completion` envelope carrying a
  `Building Name` row shows the value as `[redacted:<4hex>]` (label survives). New engine predicate
  `hasPrecedingSiblingText` is the anchor — a completion-sheet fixture is still wanted (none
  fielded yet; the entry is shape-inherited).
  - Confirmed: 0/2
  desk: desk-verifiable: compare Building Name values on both sheets with newer #1126 / PR #1213 plain-masking of numeric values; preserve the nonnumeric and completion-sheet residuals.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1468).
