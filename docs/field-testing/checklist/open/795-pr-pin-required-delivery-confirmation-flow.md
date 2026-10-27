status: review
issues: #795, #834

- **🆕 NEW — #795 / PR #834 — PIN-required delivery confirmation flow + plainMask.** The Enter-PIN
  keypad modal and intro sheet now recognize (`dropoff_pin_keypad`/`dropoff_pin_intro`,
  recognize-only), and the entered PIN masks to a **plain** `[redacted]` (no 4-hex suffix — a
  bounded PIN is brute-recoverable from 4 hex).
  **What to watch:** on the next PIN-required delivery (CVS-class), the delivery completes as
  before. **Desk:** those frames land in their intent folders instead of UNKNOWN; in the keypad
  frames' envelopes the PIN digits appear ONLY as plain `[redacted]` — no `[redacted:hhhh]` token
  on any pure-digit node, no raw PIN anywhere.
  - Confirmed: 0/2 (desk 07-26: no PIN-required delivery occurred; global `pin[\s:#]*\d` grep over
    the pull → zero raw hits.)
  desk: desk-verifiable: inspect an exercised PIN flow for recognized keypad/intro envelopes and plain PIN masks; pair it with completion events.
  Triage source: first-line blame 2026-07-23 (`722aa4c19`, original README L1347).
