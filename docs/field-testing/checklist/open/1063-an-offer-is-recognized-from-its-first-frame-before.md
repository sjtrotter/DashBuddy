status: active
issues: #1063, #595, #830, #1104, #1114, #1118, #1069

- **🆕 NEW — #1063 — an offer is recognized from its FIRST frame, before the Decline
  button inflates.** DoorDash lands the offer card in two beats: the collar animation drops the
  sheet (store leg, pay, distance, deadline, live Accept + countdown) and the decline control
  inflates a beat later. `offer_popup` required a literal `Decline` node, so that first beat fell
  to UNKNOWN — six real offers in the 09-05 slice, three of which produced no settled sibling at
  all (one was ACCEPTED off a card the app had never presented). The Decline conjunct is now
  `Decline OR the card's own accept_button id`; the #595 store-leg guard is untouched.
  **On-dash:** every offer must produce exactly ONE bubble/voice — watch for a
  "(offer replaced)" flicker or a doubled narration right at presentation, which would mean the
  early frame is being read as a DIFFERENT offer instead of the same one. Offers should also feel
  like they arrive slightly sooner (the app now sees the card on the animation frame).
  **Desk, after the pull:**
  1. `OFFER_PRESENTED` count in `app_events` == the number of offers you actually saw — no
     offer missing, none doubled.
  2. No `OFFER_TIMEOUT` carrying `Replaced by new offer` within ~2 s of an `OFFER_PRESENTED`
     (that is the #830 replace-instead-of-enrich failure this change could cause).
  3. No offer-shaped frame left in `captures/**/UNKNOWN/`: `grep -rl 'accept_decline_footer_container' captures/**/UNKNOWN/`
     should return only store-leg-LESS half-renders (the #595 family), never a card with a real
     `display_name` store row.
  - Confirmed: 1/2 (desk 09-07, the 09-06+09-07 dashes on `aabb56d0` — DESK HALF PASS, all three checks: 9 offers → 9 `OFFER_RECEIVED` → 9 resolutions (2 accepted / 7 declined) → 9 TTS utterances → 9 bubble cards, a perfect 1:1; zero "Replaced by new offer" / `OFFER_TIMEOUT`; the three offer-shaped UNKNOWN frames left are store-leg-LESS half-renders (only `accept_button` + an assignment UUID) — the #595 family the check permits. The dev-eyes half (does an offer FEEL earlier, one narration each) is still open. Desk 09-09, the 09-08 dash on `8028691a`: DESK HALF **2/2** — 17 offers → 17 `OFFER_RECEIVED` → 17 utterances → 17 screenshots → 17 resolutions (3 accepted / 14 declined), zero `OFFER_TIMEOUT` / "Replaced by new offer" / "Superseded by direct offer"; the two offer-shaped UNKNOWN frames are store-leg-LESS half-renders. The item stays open ONLY for the dev-eyes half.)
    - desk 09-13: third clean desk pass — 31 offers → 31 `OFFER_RECEIVED` → 31 utterances → 31 screenshots → 31 chat cards → 31 resolutions; zero "Replaced by new offer"/"Superseded by direct offer"; the three offer-shaped UNKNOWN frames are store-leg-less. Dev-eyes half still open. (The 7 `OFFER_TIMEOUT`s are #1104, a different mechanism.)
    - desk 09-15: fourth clean desk pass — 82 offers → 82 `OFFER_RECEIVED` → 82 utterances → 82 screenshots → 82 chat cards → 82 resolutions. Dev-eyes half still open.
    - desk 09-20: fifth clean desk pass, for 09-16/17 only — 23 offers → 23 `OFFER_RECEIVED` → 23 utterances → 23 screenshots → 23 chat cards → 23 resolutions, zero "Replaced by new offer" / "Superseded by direct offer", and `parseShortfall{doordash.screen.offer_popup…}` absent entirely (the #1063 baseline tripper stayed quiet). The 6 offer-shaped UNKNOWN half-renders are the store-leg-less #595 family the check permits. 09-18/19 contribute nothing — no offer was recognised at all (#1114). Dev-eyes half still open.
    - desk 09-21: **sixth** clean desk pass — 22 offers → 22 `OFFER_RECEIVED` → 22 utterances → 22
      screenshots → 22 chat cards → 22 resolutions on DoorDash 8.98.5's Compose card, zero "Replaced
      by new offer" / "Superseded by direct offer", `parseShortfall{offer_popup}` absent. (The 22
      `OFFER_TIMEOUT`s are the pre-#1118 outcome shape, not a recognition failure.) Dev-eyes half
      still open.
    - desk 09-26: DESK HALF CLEAN a 3rd time — 67 cards, one presentation each; zero "Replaced by new
      offer"/"Superseded"; `accept_decline_footer_container` in 0 UNKNOWN frames; all 30 offer-shaped UNKNOWN
      frames are Accept-less half-renders. One adjacent defect of a DIFFERENT class: two distinct offers merged by
      presentationKey (#1069). Dev-eyes half still open.
    - desk 09-27: DESK HALF CLEAN a 4th time — 20 cards → 20 Rule LOG receipts → 20 screenshots → 20 chat cards; 0
      "Replaced by new offer"/"Superseded"; `accept_decline_footer_container` in 0 UNKNOWN frames; the 14
      offer-shaped UNKNOWN frames are Accept-less half-renders. The one card without its own row/voice is the #1069
      merge, a different class. Dev-eyes half still open.
    - desk 09-29: DESK HALF CLEAN a 5th time — 59 cards → 59 Rule LOG → 59 TTS → 59 screenshots → 59 chat cards → 59
      resolutions; `accept_decline_footer_container` in 0 UNKNOWN frames. Two "Replaced by new offer" at +19.5 /
      +19.7 s are real card turnovers, not the ~2 s early-frame class. Dev-eyes half still open.
  Triage source: first-line blame 2026-09-05 (`5a4c54e15`, original README L632).
