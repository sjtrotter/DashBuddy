status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #433
confirmed: 0/2

- [review] **Timeline storeHint now parses + pickup_picked_up rule newly matchable (#433).**
  Two rule fixes from mojibake literals: (a) timeline task rows should now carry store names
  (watch the dash-controls overlay's task chain — logs/cards referencing timeline tasks should
  name the store, not blank); (b) the `pickup_picked_up` screen rule could NEVER match before
  (its require contained a mangled literal) — on the confirm-pickup/loading screen, watch
  whether it now classifies (bubble/log shows pickup_picked_up instead of UNKNOWN) and
  **capture it** — this intent has zero corpus snapshots.
  - Confirmed: 0/2
  desk: desk-verifiable: inspect timeline storeHint parses and pickup_picked_up recognition on the matching capture shapes.
  Triage source: first-line blame 2026-06-12 (`131d3e562`, original README L2740).
