status: active
issues: #1222

- **#1222 offer_accepting stays off a live offer card:** accept an offer and watch the next frames. Working = the "accepting" spinner frame classifies `offer_accepting`, while any frame that still shows the Accept button / "Accept" / "Add to route" stays `offer_popup` (no duplicate offer, no missed accept). Broken = a live card under `offer_accepting`, or the spinner falling to UNKNOWN. — Confirmed: 0/2
