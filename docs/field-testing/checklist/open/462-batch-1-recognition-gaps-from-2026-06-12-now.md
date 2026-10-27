status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #462
confirmed: 0/2

- [review] **Batch-1 recognition gaps from 2026-06-12 now recognized (#462).** Twelve more screens that
  fell to UNKNOWN are now recognized (mostly recognize-only — no flow change): pickup steps
  (`Pickup steps` / `Take receipt photo`), pickup "what's causing your wait" survey, pickup
  "Select an issue" + "Resolution options" menus, shopping intro-message / item-status /
  wrong-item-scanned, post-delivery "How did this delivery go?" + "Feedback about your safety",
  the alcohol ID-verify instruction checklist + "4 of 4" complete step, and the
  "You're all set to receive offers" account-checkup. Also the delivery-complete dialog now
  matches "Confirm **delivery** was completed" (it only matched "Confirm order was completed"
  before). On a dash: working = hitting any of these screens shows a recognized screen (not
  UNKNOWN) and the pickup/dropoff flow does NOT mis-step (these are recognize-only, so the
  task state should be unchanged). Broken = still UNKNOWN, or the flow jumps/regresses when one
  appears.
  - Confirmed: 0/2
  desk: desk-verifiable: census the twelve listed capture families and check recognize-only screens create no unintended flow effects.
  Triage source: first-line blame 2026-06-13 (`0ce0ef4fd`, original README L2561).
