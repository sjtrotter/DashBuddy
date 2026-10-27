status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #462, #12
confirmed: 0/2

- [review] **7-Eleven / alcohol "Verify items" pickup screen now recognized (#462, first slice).**
  The store "Verify items for <name>" screen (with "Do not open sealed bags" / "Can't verify
  items" / the item list) classified UNKNOWN — the 7-Eleven alcohol pickup from the 2026-06-12
  dash (field-log #12). It's now a recognized `pickup_verify_items` screen mapped to
  pickup-arrived (no customer-name parsing). On a retail/alcohol pickup: working = the bubble/log
  shows a pickup screen (not UNKNOWN) on the verify-items step and the flow stays on pickup.
  Broken = still UNKNOWN, or it mis-steps the flow. (**#462 stays open** — this is one of ~30
  recognition gaps from that dash; the rest are a larger effort.)
  - Confirmed: 0/2
  desk: desk-verifiable: inspect an exercised pickup_verify_items capture and its pickup-arrived transition without customer-name parsing.
  Triage source: first-line blame 2026-06-13 (`ec3e58886`, original README L2545).
