status: active
issues: #1343

- **A boosted offer card is recognized and its offer recorded (#1343):** on the next offer card that shows a
  "Very busy" boost badge (`+$N.NN` beside the label), the card is presented in the bubble and the offer is
  recorded. Working = an `OFFER_RECEIVED` (and, on accept, the accepted offer) for that card, with the card's own
  total as pay, never the badge amount. Broken = the boosted card captured as UNKNOWN, no offer event for it, or
  the badge figure read as pay. Desk: the pull holds no UNKNOWN capture of a boosted offer card. — Confirmed: 0/2
