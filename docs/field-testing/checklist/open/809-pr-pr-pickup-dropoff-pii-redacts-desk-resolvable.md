status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #809, #820, #803, #821, #885
confirmed: 0/2

- [review] **🆕 NEW — #809 / PR #820 + #803 / PR #821 — pickup/dropoff PII redacts (desk-resolvable).** New
  redacts: `pickup_select_issue` (+ its issue-list variant, now recognized) masks the fused
  `For <name> • <store>` header to `For [redacted:<4hex>]`; `dropoff_pin_entry`/`dropoff_handoff` mask
  gate-code/PIN-bearing instruction bodies (incl. `PIN: NNNN` / fused `PinNNNN` variants).
  **Desk-side:** in the next pull, every capture of these four surfaces must show the masked forms — grep
  for `For [A-Z][a-z]+\s+[A-Z]\.` and `pin[\s:#]*\d` over the recognized capture tree → zero raw hits.
  (#885: use `\s+` between the name tokens, never a single literal space — the 07-26 render put a
  DOUBLE space before the last initial and a single-space grep reads clean on a real leak.)
  - Confirmed: 0/2 (desk 07-26: both greps → zero raw hits across the whole pull, but none of the
    four specific surfaces occurred, so this is a clean null, not a confirmation.)
  desk: desk-verifiable: inspect pickup issue and dropoff envelopes for masked headers and plain instruction bodies, including the fused PIN variants.
  Triage source: first-line blame 2026-07-20 (`022633e47`, original README L1399).
