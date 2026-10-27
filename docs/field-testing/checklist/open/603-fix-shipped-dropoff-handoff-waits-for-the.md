status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / state machine
issues: #603, #526
confirmed: 0/2

- [review] **🔧 FIX SHIPPED — dropoff handoff waits for the completion CTA + stacked leg-2 drops get their own nav (#603). CONFIRM ON DASH.**
  Two coupled fixes for the false-early "arrived" + silent second-drop pair:
  (a) **No more false ARRIVED at the start of the drive.** `dropoff_handoff` used to fire
  `task:dropoff:arrived` the instant nav started, because it keyed only on the "Hand it to
  customer" instruction — which is on screen the ENTIRE drive. It now also requires the completion
  CTA ("Mark as delivered" / "Continue" / "Complete Delivery" / the complete-delivery button), which
  only shows once you're at the door. The en-route frame now recognizes as `dropoff_pre_arrival`
  (correct nav card). **How to tell it works:** on a drop with a real drive, the arrival card /
  `DELIVERY_ARRIVED` should appear only when you actually reach the customer, NOT the moment you
  start driving. In `app_events` the drop's `arrivedAt` should be meaningfully later than its
  `phaseStartedAt` (not the same second). Broken = the "arrived"/hand-off card popping up while
  you're still driving, or `arrivedAt == phaseStartedAt`.
  (b) **Leg-2 of a stack gets its own "Heading to" bubble.** A stacked multi-drop's second (and
  later) dropoff previously got no nav event — no `DELIVERY_NAV_STARTED`, no odometer resume, no
  bubble — because the mint only fired on pickup→dropoff, and leg-2 is dropoff→dropoff. **How to
  tell it works:** on a stack with 2+ drops, after finishing drop 1 you should see a fresh "Heading
  to …" bubble for drop 2 and a `DELIVERY_NAV_STARTED` row for it in `app_events` (and the odometer
  resumes for that leg). Broken = the second drop starting silently with no bubble / no
  `DELIVERY_NAV_STARTED`. (Note: leg-2's store label may read the generic "the customer" until #526
  widens store re-attribution — that's expected, not a regression.)
  - Confirmed: 0/2
  desk: desk-verifiable: correlate handoff/completion captures with arrival events and verify each stacked second drop gets its own navigation lineage.
  Triage source: first-line blame 2026-07-03 (`ad9b4e6d3`, original README L2071).
