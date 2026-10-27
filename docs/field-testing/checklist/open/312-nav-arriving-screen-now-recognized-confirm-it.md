status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #312
confirmed: 0/2

- [review] **`nav_arriving` screen now recognized — confirm it fires + gauge frequency
  (PR #312).** The in-app nav "Arriving at \<destination\>" / "Arriving soon"
  overlay was UNKNOWN; it now classifies as `nav_arriving` (neutral — no behavior
  change yet). On a dash, glance at whether this screen actually appears as you
  reach a stop, for **both pickups and dropoffs**, and roughly how often (the
  capture corpus only caught it ~5/50 times — we need to know if that's
  capture-cadence or it genuinely doesn't show every approach). This decides
  whether "Arriving" can be the **arming** signal for the nav-exit arrival model
  ([design](../../../capture-analysis/2026-06-task-arrival-navexit-model.md)). What to
  watch: does "Arriving at …"/"Arriving soon" reliably show on final approach?
  - Confirmed: 0/2.
  desk: desk-verifiable: census nav_arriving captures near pickup/dropoff timestamps and estimate frequency only where those surfaces occurred.
  Triage source: first-line blame 2026-06-08 (`d265b8c25`, original README L3012).
