status: review
issues: #462

- **Order-ready push notification now recognized (#462).** The "‹name›'s order is ready for pickup
  at ‹store›" push arrives on the `dasher-notification-background` channel (it was UNKNOWN before).
  Working = when DoorDash sends the order-ready notification, the log shows it classified
  (`ORDER_READY`), not UNKNOWN — and the customer name is never stored (the rule logs a constant).
  Broken = still UNKNOWN, or a customer name shows up parsed.
  - Confirmed: 0/2
  desk: desk-verifiable: correlate order-ready notification captures with constant ORDER_READY logs and absence of persisted customer names.
  Triage source: first-line blame 2026-06-13 (`1a2bf6bf3`, original README L2585).
