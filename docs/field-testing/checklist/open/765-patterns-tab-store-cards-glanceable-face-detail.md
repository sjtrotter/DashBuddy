status: review
section: §3 Review (aged ≥ 6 weeks at 0/2) / analytics
issues: #765, #799, #979, #1024
confirmed: 0/2

- [review] **🆕 NEW — Patterns tab store cards: glanceable face + detail bottom sheet (#765 / PR #799).**
  The store report cards were redesigned: the card **face** now shows only store name + location chip
  and three plain-language numbers (Net / Usual wait / Deliveries) — no "median"/"p95" vocabulary —
  with a `>` chevron affordance. Tapping a card opens a **bottom sheet** with the full detail (pickups,
  gross, the dwell distribution labeled "Usual wait (median)" / "Longest waits (p95)" / "Average wait",
  and first/last-seen). **What to watch:** open Analytics → Patterns; the store cards read at a glance
  (2–3 numbers, no stats jargon on the face); tapping a card opens the sheet with the fuller breakdown;
  swipe/scrim dismisses it; "usual wait" on the face matches the median in the sheet; a location-unknown
  (chain-only) card still shows its "partial" note inside the sheet.
  - Confirmed: 0/2
  desk: needs field: inspect store detail-sheet interactions in the current Playbook destination; later #979/#1024 changed the surrounding store layout.
  Triage source: first-line blame 2026-07-18 (`07b4fd86b`, original README L1490).
