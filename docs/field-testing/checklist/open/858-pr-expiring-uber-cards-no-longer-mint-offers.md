status: review
issues: #858, #876

- **🆕 NEW — #858 / PR #876 — expiring Uber cards no longer mint offers.**
  The "This request is no longer available" dying card is UNKNOWN or a recognize-only Trip Radar
  board frame with no `state` block: no state, no offer. **What to watch (Uber):** an offer that expires on screen produces no new offer row, no TTS
  read of the error text, no "(offer replaced)" churn on the live offer. **Desk:** zero
  `merchantName` containing "no longer available" / "Unknown Store"-via-overlay in `offer_records`;
  the dying frames appear as UNKNOWN or `trip_radar_board` captures.
  - Confirmed: 0/2
  desk: desk-verifiable: compare expiring Uber-card captures with offer rows and TTS logs; error overlays must mint no new offer.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1457).
