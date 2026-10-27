status: review
issues: #691, #702, #996, #997

- **🆕 NEW — receipt-less shop delivery shows est. offer pay, not $0-unattributed (#691 / PR).**
  Do a **DoorDash shop order** (grocery/convenience — the kind that shows NO per-delivery receipt at
  the end; pay lives only on the offer + the running dash total). After it completes, open **Analytics
  → the dash → the delivery drill-down**.
  How to tell it's working: (1) the delivery row shows a **real pay figure with an "est. offer pay"
  qualifier** under it, NOT a `$0` / "Unknown"-style row, and the Money-tab unattributed callout
  shrinks accordingly. (2) On a **receipt-less shop STACK** (2+ orders, no receipt), the two drop rows
  are **≈ equal halves of the offer pay shown at accept** (e.g. a $12.95 stack → ~$6.48 / ~$6.47),
  summing to the offer total — not one drop taking the whole thing and the other $0. (3) A shop order
  that DID show a receipt still uses the receipt (basis DROP_SHARE/RECEIPT_TOTAL), no "est." qualifier.
  **NOTE (expected, not a bug):** net for a receipt-less shop is now **pay − mileage cost**, which is
  **LOWER** than the old unattributed-callout number (that number was raw pay with no cost) — a lower,
  more honest figure is correct. (#691 / PR)
  - Confirmed (mechanism): 2/2 — RETIRED for the mechanism half. 07-08: first firing (OFFER_PAY
    $14.25, session reconciled exactly). 07-12 (2026-07-13 desk pass): second independent firing —
    receipt-less H-E-B shop folded OFFER_PAY $10.75 alongside a RECEIPT_TOTAL sibling in the same
    session, reconciled to the cent; regression watch (5) also clean (no $0-coerced rows despite
    collapsed-summary frames). Item stays open ONLY for the dev-eyes halves: (1) the "est. offer
    pay" qualifier display, (2) a receipt-less stack's equal halves, (4) Uber-scope sanity.
  - **(4) 🆕 UBER SCOPE (PR #702 round 2): Uber deliveries now show est. offer pay platform-wide.**
    Uber has no post-trip receipt rules at all (`uber.screen.post_trip` has no parse), so EVERY Uber
    delivery now folds an OFFER_PAY estimate (the platform-agnostic mechanism working as designed, P8).
    This changes Uber analytics **wholesale** — **sanity-check the est. offer pay against your actual
    Uber earnings** (the offer's guaranteed quote vs what Uber actually paid, incl. surge/tips added
    after). Flag any drift.
  - **(5) 🆕 REGRESSION WATCH (PR #702 round 2): a collapsed/transient summary must NOT force $0.**
    On a receipt-less drop, watch that the row shows the **est. offer pay**, NOT a `$0.00` receipted
    row — a transient `delivery_summary_collapsed` frame used to coerce a `$0` pseudo-receipt that
    masked the estimate. If any receipt-less drop reads `$0.00` (basis RECEIPT_TOTAL) instead of an
    est. figure, capture the session and flag it.
  - Confirmed: 0/2
  desk: needs field: inspect the estimate qualifier and an exercised receipt-less stack; #996/#997 now replace pooled attribution for some shapes, so old equal-halves copy needs dev review.
  Triage source: first-line blame 2026-07-06 (`dc9315f2e`, original README L1766).
