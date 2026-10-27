status: active
section: §4 1/2 items older than 6 weeks / analytics
issues: #996, #997, #1012, #691, #1119
confirmed: 1/2

- [active] **🆕 NEW — #996/#997 (PR #1012) — per-offer pay attribution on a receipt-less dash.** On any
  dash with no post-drop receipt (an out-of-zone "Dash Along the Way" start, or a shop order),
  watch three shapes. (a) **Multi-accept job at DIFFERENT stores:** each drop's drill-down shows
  its own store's offer pay as the "est. offer pay", not all drops showing one averaged number.
  (b) **Multi-accept job at the SAME store:** those drops show the same sub-pooled number — that
  is correct, not the old bug. (c) **Same-customer multi-order job:** the single physical drop
  carries the FULL quoted offer pay and the Money tab's unattributed remainder for that dash is
  $0.00 — pre-fix it was exactly half the quote. **Desk:**
  `SELECT d.jobId, COUNT(*), SUM(d.realizedPay) FROM delivery_records d WHERE d.payBasis='OFFER_PAY' GROUP BY d.jobId;`
  — each job's Σ ≤ its accepted offers' Σ `payAmount`, equal whenever every offer's store matched
  a drop. `DELIVERY_COMPLETED` payloads now carry `offerPayAttribution`
  (`PER_OFFER_STORE`/`SUB_POOLED_STORE`/`STAMP_FALLBACK`/`CONSOLIDATED_CUSTOMER`/`JOB_POOLED`/
  `INLINE_POOLED`). Log greps: `#997 offer-pay attribution degraded to` (DEBUG, per-degrade with
  counts — a `JOB_POOLED` on a job whose drops all had stores means a store failed to reconcile);
  `#997 offer pay unattributed at close` (WARN — accepted money that found no drop);
  the `#691` WARN now reports `denominator=N of M owed` + `ownOfferPay=present|null`.
  - Confirmed: 1/2 (desk 08-24: shape (a) confirmed — 5/5 week drops `PER_OFFER_STORE`, the
    Michaels+Petsmart job split $19.70 → $9.85/$9.85 across its two stores, Σ stamped = Σ quotes
    to the cent, zero degrades; the slice's one `STAMP_FALLBACK` (08-14, store-less drop) also
    reconciled exactly. Shapes (b) sub-pooled and (c) consolidated-customer still unseen.)
    - desk 09-21: degraded exactly twice, and both degrades are the machine reporting #1119
      honestly — 2 × `#997 offer-pay attribution degraded to JOB_POOLED … 0 offer(s) over 1 drop(s)`
      + 2 × `#691 … denominator=1 of 1 owed, ownOfferPay=null, jobOfferTotal=null`, all four on the
      lost-accept jobs …-668 and …-687. Not an attribution defect: there was no offer to attribute.
      Shapes (b) and (c) still unseen.
  Triage source: first-line blame 2026-08-09 (`f41237874`, original README L805). Recorded first confirmation: desk 2026-08-24 (shape a only) (existing evidence only).
