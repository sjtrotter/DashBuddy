status: active
issues: #596, #499, #503

- **🔧 FIX SHIPPED — receipt-skipped deliveries still close + log a completion, and the next offer starts a NEW job (#596).**
  DoorDash routinely skips the post-delivery receipt (the next offer chains straight over the drop);
  pre-#596 that was the machine's ONLY job-exit, so the delivery never logged `DELIVERY_COMPLETED`
  and the never-closed job absorbed later independent offers (06-29 Pizza Hut job swallowed the next
  H-E-B $13.50; 06-30 job-61 spanned three offers). Now a *physically-complete* job (final drop
  delivered, nothing outstanding) closes on its next exit signal even with no receipt: the drop logs
  one `DELIVERY_COMPLETED` and the next accepted offer mints a fresh jobId.
  **Confirm on next pull: 0/2 —** after a delivery where DoorDash skips the receipt (you go from the
  drop's handoff/nav straight to waiting-for-offer or the next offer, no earnings summary), the db
  should still show a `DELIVERY_COMPLETED` for that drop, and the next accepted offer should carry a
  **distinct jobId** (no multi-offer job unless it's a genuine mid-route add-on you accepted while
  still delivering). Broken = a receipt-skipped drop with no completion, or two independent offers
  sharing one jobId. **Watch the guard:** a genuine mid-shop/mid-route add-on (accepted while a drop
  is still undelivered) must STILL fold into the same job — it must not regress into a new jobId
  (#499/#503).
  Triage source: first-line blame 2026-07-02 (`9bdb490a3`, original README L2216).
