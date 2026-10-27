status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #349
confirmed: 0/2

- [review] **Screenshots still save + no offer-time jank (#349).** Screenshot saving moved fully off the
  main thread (the PNG compress + MediaStore write used to run on main, right when the offer
  card/notification renders). Confirm: (a) offer + dash-summary screenshots still land in
  `Pictures/DashBuddy`; (b) no visible stutter the moment an offer arrives (should be same or
  smoother than before).
  - Confirmed: 0/2.
  desk: needs field: verify saved screenshots and observe offer-time stutter; successful files alone cannot establish smooth rendering.
  Triage source: first-line blame 2026-06-10 (`07c9c45bd`, original README L3050).
