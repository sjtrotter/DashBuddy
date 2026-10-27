status: review
issues: #4

- **Self-recognition fixed: our own bubble is no longer parsed as a DoorDash offer (#4, PR pending).**
  Root cause of the 2026-06-09 offer flip-flop: when the bubble was the active window over DoorDash,
  our own overlay got snapshotted, mislabeled `doordash`, and matched `offer_popup` → a phantom
  re-eval (the spurious DECLINE-6 / "22.5 mi"). Now active-window snapshots are attributed to the
  window's real package (our overlay is dropped), and the offer rule demands the `accept_button`
  structure our overlay lacks. To test: on an offer, **open the bubble** over the DoorDash offer →
  confirm the verdict / notification / spoken read **stay stable** (no flip to DECLINE, no re-eval)
  while the bubble is up. Watch the log for `🚫 Skip active window: non-target pkg=cloud.trotter.dashbuddy`
  (proof our overlay is being dropped) and **no** second `offer_popup` classification. Real orders.
  - Confirmed: 0/2.
  desk: desk-verifiable: correlate active-window package attribution with offer captures; the app's own overlay must create no DoorDash offer.
  Triage source: first-line blame 2026-06-09 (`27410a24c`, original README L2813).
