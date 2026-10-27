status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / state machine
issues: #786, #869, #251
confirmed: 0/2

- [review] **🆕 NEW — #786 / PR #869 — Uber decline click rule (the X is recognized).**
  The offer card's textless dismiss-X now latches `declineCommittedAt` → declines record as
  `OFFER_DECLINED` instead of the timeout ignorance-default. **What to watch (Uber):** the bubble's
  Dispatch line says "Offer Declined" on your X taps; **THE CRITICAL GUARD — an Uber dash with a
  REAL accepted, driven offer must produce `OFFER_ACCEPTED` + a costed job and NEVER a false
  `OFFER_DECLINED`** (the mislatch hazard the review closed with the own-text-empty predicate; if
  an accept ever records as declined, pull the build and report — that's the revert condition).
  **Desk:** `offer_records` Uber outcome census gains OFFER_DECLINED rows ≈ witnessed X taps
  (~claimed 4/5 in the 07-25 pull); unwitnessed disappearances stay TIMEOUT pending #251.
  - Confirmed: 0/2
  desk: needs field: obtain a real Uber accepted/driven offer and a dismiss-X decline, then check costed jobs and outcome events for false declines.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1447).
