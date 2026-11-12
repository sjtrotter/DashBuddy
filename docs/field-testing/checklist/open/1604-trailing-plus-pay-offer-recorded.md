status: active
issues: #1604

- **A total offer card whose pay reads with a trailing plus is recorded (#1604):** on the next DoorDash offer whose
  pay reads `$N.NN+` above `incl. tips` (often with a `High pay for …` badge and a `Shop & deliver` chip), the card is
  presented in the bubble and the offer is recorded. Working = an `OFFER_RECEIVED` (and, on accept, the accepted
  offer) for that card, with pay N.NN (the plus dropped). Broken = the card captured as UNKNOWN or claimed by the
  side menu, or no offer event for it. An add-on card (`+$N.NN`, a leading plus) still reads as an add-on. Desk: the
  pull holds no UNKNOWN capture of a fully drawn trailing-plus card. — Confirmed: 0/2
