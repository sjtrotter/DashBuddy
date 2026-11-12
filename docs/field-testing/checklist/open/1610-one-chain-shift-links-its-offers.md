status: active
issues: #1610

- **A one-chain shift links its offers (#1610):** on a shift where one store chain sends most of your offers, every offer you accept still becomes its job's offer. An offer you let run out with DoorDash's decline sheet still open ("Declined", the countdown at "0:00") ends the shift as a decline and never competes for later jobs at that chain; a card DoorDash shows after you declined another at the same chain links on its own instead of tying with the declined one. Compare at the shift end: the accepts the app recorded against DoorDash's own count (Dash summary "Offers accepted N out of M"); they should match.
  - desk (1): the tie WARN (`#1119 … pooled offers tie at job …`) count for the shift; on a one-chain shift it should be near zero. Each one is either two or more cards that left without a decision, or one card presented before a same-chain sheet decline that left within 60 s of the work (the WARN's `(N demoted)`).
  - desk (2): timer leaves demoted by the sheet: the DEBUG pool line `pooled via EXPIRY_TIMER … demoted by the decline sheet standing until the timer`. Each one ends the shift `OFFER_DECLINED` with `declineEvidence` `SHEET_WINDOW`; none of them is an offer DoorDash counted as accepted.
  - desk (3): a timer leave with no sheet sighting (DEBUG `pooled via EXPIRY_TIMER` with no suffix) followed by work at its store within seconds still links (INFO `#1119 offer … linked to job …`).
  - desk (4): every INFO link line within 60 s of a same-chain sheet decline names a card presented after that decline left the screen.
  - Confirmed: 0/2
