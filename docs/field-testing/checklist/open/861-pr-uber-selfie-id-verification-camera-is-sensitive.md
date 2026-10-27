status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / recognition
issues: #861, #868
confirmed: 0/2

- [review] **🆕 NEW — #861 / PR #868 — Uber selfie ID-verification camera is sensitive-blocked.**
  **Desk only:** next pull has ZERO selfie-flow UNKNOWN envelopes (`facecamera` ids /
  "Fit your face in the guide"); `sensitiveDropped` increments across that window instead.
  - Confirmed: 0/2
  desk: desk-verifiable: correlate an exercised selfie-flow window with sensitiveDropped and absence of facecamera/guide envelopes.
  Triage source: first-line blame 2026-07-26 (`a71ad28d8`, original README L1464).
