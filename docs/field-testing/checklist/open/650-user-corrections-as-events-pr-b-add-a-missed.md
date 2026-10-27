status: active
issues: #650, #688

- **🆕 NEW — user corrections as events (#650 PR B): add a missed delivery, adjust a pay.** On a dash
  that has an **"unaccounted on this dash"** callout (Analytics → Money → tap the dash), tap **Add
  missed delivery**, enter a pay (optionally store/tip/note), confirm. How to tell it's working: within
  a moment the callout **shrinks or disappears** and a new delivery row appears (store you typed, the
  pay, marked as a manual/driver entry). Then tap the **edit (pencil) icon** on any delivery row,
  change the pay, save: the row **re-prices** and the header gross / period totals follow. On a dash
  with **no** callout, the **Add missed delivery** button still appears at the bottom of the deliveries
  card. Cross-check nothing was destroyed: the original captured rows are still present (a correction is
  an added event, not an overwrite). How to tell it's broken: the callout doesn't move after adding, the
  new row never appears, the re-price doesn't stick (or reverts on the next screen refresh), or a
  previously captured delivery vanishes. (PR #650-B)
  - Confirmed: 1/2 — 2026-07-05: exercised heavily — 5 PAY_ADJUSTMENTs across 3 rows; callout reconciled to $0 exactly (104.47 = 104.47); re-price stuck (incl. a two-try edit); nothing destroyed (all original events in the log). UX gaps → #688.
  Triage source: first-line blame 2026-07-05 (`dd0df7acc`, original README L1833). Recorded first confirmation: 2026-07-05 (existing evidence only).
