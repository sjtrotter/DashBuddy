status: active
issues: #864

- **Resolved offer cards no longer wear a verdict banner (#864):** after an offer times out or is declined, expand
  its card in the HUD — the big ACCEPT/DECLINE advice banner is gone and only the header's outcome chip (Accepted /
  Declined / Timed out) states what happened; a LIVE card still shows the banner. Working = no resolved card can be
  misread as "declined" from the banner alone.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`5ff90c7d0`, original README L110).
