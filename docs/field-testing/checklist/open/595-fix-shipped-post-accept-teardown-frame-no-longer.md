status: active
issues: #595

- **🔧 FIX SHIPPED — post-accept teardown frame no longer mints a ghost offer (#595). CONFIRM ON DASH.**
  The collapsing offer card after an accept (pay/distance/Accept/Decline chrome, no store rows, a
  raw UUID where the store name goes) re-parsed as a NEW "Unknown Store" offer that REPLACED the
  real accept — bogus TTS ("Accept. Unknown Store. 56/hr…"), a bogus [Good Offer] chat card, then
  "Offer Timed Out!" seconds after "Offer Accepted" (both 06-28 + 06-30). The offer rule now
  requires at least one STORE leg, so the teardown frame is UNKNOWN and never forwarded.
  **Confirm on dash: 0/2 —** accept a few offers (especially SHOP offers, where both field ghosts
  fired): right after tapping Accept there should be NO second offer announcement — no "Unknown
  Store" TTS, no extra [Good Offer] card, no "Offer Timed Out!" right after "Offer Accepted"; the
  db should show zero orphan OFFER_TIMEOUTs. **Watch for over-rejection:** a REAL offer failing to
  announce/card (its frame would land in UNKNOWN captures as an offer-looking screen with store
  rows) — grab the capture if an offer ever silently fails to appear.
  Triage source: first-line blame 2026-07-02 (`edc269a12`, original README L2261).
