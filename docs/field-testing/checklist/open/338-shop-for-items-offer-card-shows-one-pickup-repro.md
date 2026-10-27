status: review
issues: #338, #5, #324

- **Shop-for-items offer card shows ONE pickup (repro watch; #338).** On the next HEB/grocery
  shop-for-items offer, screenshot the bubble offer card: it must list the store once. The
  2026-05-17 #5 duplicate's parse-layer cause was ruled out 2026-06-10 (every captured HEB offer
  frame parses exactly one order), and the card was redesigned in PR #324 — so this watches for
  recurrence. If it shows two pickups again, grab the `offer_popup` capture + a screenshot pair so
  parse vs render can be split.
  - Confirmed: 0/2.
  desk: needs field: pair a grocery offer capture with its visible card to resolve the reported duplicate-pickup presentation.
  Triage source: first-line blame 2026-06-10 (`f2a700a5b`, original README L3043).
