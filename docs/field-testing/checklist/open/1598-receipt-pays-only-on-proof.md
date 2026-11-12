status: active
issues: #1598, #1326

- **A receipt pays a delivery only on proof (#1598):** on the next shift, a single delivery from an offer card that
  read `Customer dropoff` shows its receipt pay on the shift page. A stacked delivery, or any job that took more than
  one offer, shows "Offer amount, est." or no pay on its drops, never one receipt split across them. Desk: the
  `#1598 per-receipt reconciliation` query in `desk-validation-playbook.md` puts each saved receipt beside the pay it
  placed (PROVEN_SINGLE places its whole total on its drop, UNPROVEN places nothing), and `GROUP BY proofGap` shows
  how often each clause refuses; STORE_MISSING and CARD_COUNT_MISSING on Compose cards are the ones to watch. Broken =
  one receipt split across drops, or an UNPROVEN receipt with placed money.
  — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): not on the phone. Desk estimate: the 6 single-drop receipts would be `PROVEN_SINGLE`; the 11-07 two-drop receipt would be `CARD_COUNT_NOT_ONE` (the drops keep the offer estimate), unless the pool ties first (#1610, see the 1119 offer-pool item), in which case the gap would be `OFFER_COUNT`. Counter unchanged.
