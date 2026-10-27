status: field-validated
section: §5 Field-validated / retired
issues: #302, #276, #4
confirmed: 2/2

- [field-validated] **Shop & Deliver items/min reaches `total/total` at the end (#302).** On a
  Shop & Deliver order, when you finish shopping (add the last item / reach
  "To shop (0)") the bubble shop card should read **`shop total/total`** — not
  `total−1/total` — and the items/min pace should reflect the full count. This was
  the off-by-one from the 2026-06-05 session, caused by the terminal frame being
  deduped away; the fix makes each shopping count change a distinct observation.
  - Confirmed: 2/2. **2026-06-06 (DoorDash):** developer reported "the item counts
    are working" (no longer freezing one short). **2026-06-12 (DoorDash):** pickup/shop
    card read **`shop 25/25 · 0.6/min`** at end of shop — the terminal `total/total`
    frame, no longer `total−1/total`. Two clean sightings → **validated** (the add-on
    case is tracked separately under the #276 watch item). (See 2026-06-12 log entry #4.)
  Triage source: first-line blame 2026-06-06 (`e723d4fe6`, original README L2975).
