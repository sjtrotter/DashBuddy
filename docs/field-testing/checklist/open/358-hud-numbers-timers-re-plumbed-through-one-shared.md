status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #358, #386
confirmed: 0/2

- [review] **HUD numbers/timers re-plumbed through one shared format/time kit (#358, PR #386).**
  All bubble-card money, distance, countdown, and duration strings now come from
  `:core:designsystem` helpers, and the phase chip switched to brand tokens. On a normal dash,
  glance-check: (a) card money/mi/min strings look exactly as before (en-US should be visually
  unchanged); (b) the offer countdown and elapsed timers still tick per second; (c) the phase
  chip color now MATCHES the card's status colors (OFFER chip = same blue family as the offer
  status badge, PAID = green) instead of the old purple/teal M3 roles.
  - Confirmed: 0/2.
  desk: needs field: observe card formatting, per-second timers and phase colors together on the device.
  Triage source: first-line blame 2026-06-11 (`670a76c5a`, original README L2776).
