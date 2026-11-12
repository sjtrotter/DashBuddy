status: active
issues: #1222

- **#1222 offer_accepting stays off a live offer card:** accept an offer and watch the next frames. Working = the "accepting" spinner frame classifies `offer_accepting`, while any frame that still shows the Accept button / "Accept" / "Add to route" stays `offer_popup` (no duplicate offer, no missed accept). Broken = a live card under `offer_accepting`, or the spinner falling to UNKNOWN. — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): on the Compose card the accept teardown frames (11-11 15:58:32, 16:43:05) fall to UNKNOWN. `offer_accepting` has only View branches, so the item cannot pass on the Compose card as written. Counter unchanged.
