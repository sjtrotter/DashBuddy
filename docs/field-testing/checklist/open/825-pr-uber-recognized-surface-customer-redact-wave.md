status: review
issues: #825, #833

- **🆕 NEW — #825 / PR #833 — Uber recognized-surface customer redact wave.** `active_trip`,
  `customer_chat`, `splash`, and `pickup_verification_items` now carry full redact blocks (content
  shapes + uber id anchors incl. the chat `headline_text` "<Name> says:" header and the
  `map_marker_pin_head` address that rides `contentDescription` only).
  **What to watch:** nothing visible on-dash — this is capture-side. **Desk:** in the next pull,
  every RECOGNIZED uber envelope must show `[redacted:<4hex>]`/`[redacted]` on customer
  name/street/gate nodes and zero raw customer tokens (grep the recognized uber captures with the
  CLAUDE.local.md name/address recipe; the 07-21 pull's 4 leaking active_trip frames are the
  before-picture). Chat header masks must be per-customer-stable (same hex as the bare name).
  - Confirmed: 0/2 (desk 07-26: INCONCLUSIVE — zero Uber `active_trip`/`customer_chat`/
    `pickup_verification_items` frames exist in the pull (no trip was ever accepted). The Uber
    surfaces that DID capture show correctly-masked dropoff lines.)
  desk: desk-verifiable: inspect the four named Uber capture families, including contentDescription-only addresses, for their specified masks.
  Triage source: first-line blame 2026-07-23 (`722aa4c19`, original README L1335).
