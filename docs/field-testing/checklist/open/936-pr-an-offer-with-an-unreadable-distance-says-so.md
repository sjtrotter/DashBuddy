status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / HUD
issues: #936, #952
confirmed: 0/2

- [review] **🆕 NEW — #936 / PR #952 — an offer with an unreadable distance says so instead of guessing.**
  If a card's mileage ever fails to parse, the HUD/notification should show `no verdict` +
  `distance didn't parse` (no `$0/hr`, no score gauge) and the voice should say *"No verdict — the
  distance didn't parse"*. Rare in the field; the desk check is `offer_records` rows with
  `quality = 'UNKNOWN'` and a null `distanceMiles` — their `score`/`estDollarsPerHour` columns
  must be **null**, never `0`.
  - Confirmed: 0/2 (desk 07-31, the 07-30 dash: the whole `offer_records` table — 383 rows,
    lifetime — shows 0 null `distanceMiles`, 0 `quality='UNKNOWN'`, 0 `score=0`. The failure mode
    never occurred on this device; a clean null result, not a confirmation of the fallback path.
    Stays at 0/2 pending an offer that actually fails to parse distance.)
  desk: desk-verifiable: inspect UNKNOWN-quality offer rows with null distance for absent score/rate; the no-verdict display and voice still need device observation.
  Triage source: first-line blame 2026-07-30 (`d3f31f580`, original README L1047).
