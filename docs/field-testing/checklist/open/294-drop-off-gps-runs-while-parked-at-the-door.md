status: active
issues: #294, #438, #918

- **Drop-off GPS runs while parked at the door (#294 — CONFIRMED at desk 08-09, dev-eyes half
  only).** The mechanism is now known and the old item text was inverted: post-#438 B5 the
  odometer keys on `lastActedFlow ∈ STATIONARY_FLOWS` (transient — the dropoff-completion
  screens photo/PIN/confirm are NOT in the set, so they read *moving* and resume GPS) while the
  HUD's AT DOOR keys on the sticky `arrivedAt`. The 08-09 pull showed the resume firing 10–20 s
  after arrival on ~every dropoff (pickups hold correctly). Desk half is DONE; what remains is
  dev eyes on the fix once one of the three candidate directions on the issue ships — until
  then, expect session-miles to creep at the door (it pairs with #918's jitter). No action
  needed on-dash beyond awareness.
  - Confirmed: 1/2 (desk 08-09: mechanism confirmed with per-dropoff timings; awaiting the fix
    + one field confirmation of the fixed behavior).
  Triage source: first-line blame 2026-08-09 (`f41237874`, original README L3032). Recorded first confirmation: desk 2026-08-09 (mechanism; fix still awaited) (existing evidence only).
