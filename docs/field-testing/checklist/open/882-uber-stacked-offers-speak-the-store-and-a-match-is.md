status: review
issues: #882, #881

- **🆕 NEW — #882 / #881 — Uber stacked offers speak the STORE, and a "Match" is recorded as a match.**
  Two fixes on the same card. **#882:** a multi-order Uber card ("Delivery (2)") rendered its type
  chip above the one store line it shows, and the chip won the store read — so the bubble/TTS said
  *"Offer. Delivery 2."* and `offer_records.merchantName` recorded "Delivery (2)". The **display**
  now falls through to the visible store ("Sonic (3035 Tpc Pkwy)"); the offer's internal identity
  deliberately still uses the chip, so a stack card can't flicker into looking like a new offer.
  **#881:** the card's CTA ("Match" = a Trip-Radar match, "Accept" = a direct offer) is now recorded
  as `offerKind`, and a real offer arriving over a match is narrated as an expected hand-off instead
  of the loud "(offer replaced)" card.
  **What to watch (Uber dash):**
  1. **A stacked offer speaks a real store name** — never "Delivery 2" / "Shop and Deliver 3". A
     single-store Uber offer must still speak exactly what it did before.
  2. **No store name goes missing.** If an Uber offer suddenly speaks "Unknown Store", that's this
     change over-rejecting — note the store and the card shape.
  3. **A direct offer landing on top of a "Match" card** should NOT pop a "Declined/Expired (offer
     replaced)" chat card any more; the new offer just takes the surface.
  4. **Trip time (desk-only — nothing to see while driving).** An Uber card that also shows
     "Arrive around 8:39 PM • 1 min away" was recording the 1-minute ETA as the trip time instead
     of the *"14 min (3.1 mi) total"* line. This never touched the verdict or the spoken $/hr —
     the evaluator derives its own estimate from distance + handling — so there is **no on-dash
     tell**; it corrupted the recorded time, the offer's identity hash, and the accept-time
     estimate fallback. Check it in the pull, not in the car (below).
  **Desk (next pull):** `offer_records` for Uber should carry real merchant names (zero rows where
  `merchantName LIKE 'Delivery (%'`); `OFFER_TIMEOUT` payload descriptions should show
  `Superseded by direct offer` wherever a direct offer preempted a match; and in the
  `OFFER_RECEIVED`/closing payloads, `parsedOffer.timeToCompleteMinutes` should equal the card's
  own *"N min (M.M mi) total"* number — never a 1–2 minute value that matches an "N min away"
  ETA banner on the same frame.
  **Still wanted:** multi-frame captures of ONE lingering stack card — if Uber CYCLES which store a
  stack shows across re-renders, the desk can finally settle whether identity may follow the store
  (today it deliberately does not).
  - Confirmed: 0/2
  desk: needs field: an Uber stack and Match-to-direct hand-off must occur; then compare merchant, trip-time and outcome rows with captures.
  Triage source: first-line blame 2026-07-27 (`1f4c4a940`, original README L1140).
