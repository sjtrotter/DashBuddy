status: active
issues: #1604

- **A total offer card whose pay reads with a trailing plus is recorded (#1604):** on the next DoorDash offer whose
  pay reads `$N.NN+` above `incl. tips` (often with a `High pay for …` badge and a `Shop & deliver` chip), the card is
  presented in the bubble and the offer is recorded. Working = an `OFFER_RECEIVED` (and, on accept, the accepted
  offer) for that card, with pay N.NN (the plus dropped). Broken = the card captured as UNKNOWN or claimed by the
  side menu, or no offer event for it. An add-on card (`+$N.NN`, a leading plus) still reads as an add-on. Desk: the
  pull holds no UNKNOWN capture of a fully drawn trailing-plus card. — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): pre-fix, broken as expected. A full `$27.35+` card fell to UNKNOWN (11-11 14:32:26), a lost decline; the `$12.15+` card at 14:51 was accepted with no offer recorded (the job priced only by its $19.15 receipt). DoorDash 12 vs app 11 offers on shift 1; 3 vs 2 accepted on shift 2. Counter unchanged.
