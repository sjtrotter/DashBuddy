status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #528
confirmed: unspecified

- [active] **🔧 FIX SHIPPED — a stacked job's DELIVERY_COMPLETED rows now carry per-drop realized pay (#528 Slice A). READ THE DB AFTER A DASH.**
  Before, on a multi-store/multi-drop stack, the single combined receipt was attached to just ONE
  drop (it absorbed the whole total) and every other drop's `DELIVERY_COMPLETED` row recorded null
  pay. Now each row carries `dropRealizedPay` = the exact per-store tip matched to that drop + an
  **equal-split** share of the lump base; when the stack settles with a single end-of-job receipt
  (the normal DoorDash shape) the job's drops sum EXACTLY (to the cent) to the receipt total.
  Same-store batches and blank/duplicate store names fall back to a pure
  equal-split, so no drop double-counts a tip line. Tips are exact; **the base split is a v1
  estimate** (neither offer nor receipt breaks out per-order base). (A mid-stack/per-drop receipt
  can under-attribute — never double-count — tracked as a #528 follow-up.) This is a data-fidelity change
  only — no bubble/HUD copy changed (the card still shows the blended job $/mi; consuming the new
  field in the UI is a later slice). **Confirm after dash: 0/2 —** run a **multi-store stack**
  (2+ drops from different stores in one job) to completion, then read the db `app_events`: the
  `DELIVERY_COMPLETED` rows for that job should each have a non-null `dropRealizedPay`, and summing
  them should equal the combined receipt total (the `parsedPay.total` on the announced row).
  Broken = a drop with null `dropRealizedPay` on a receipted stack, or the per-drop shares not
  summing to the receipt total.
  Triage source: first-line blame 2026-07-03 (`44ab7a3ff`, original README L2002).
