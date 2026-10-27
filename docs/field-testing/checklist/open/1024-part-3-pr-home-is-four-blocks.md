status: review
issues: #1024, #1027

- **🆕 NEW — #1024 part 3 (PR #1027) — Home is four blocks.** One **Today** card (kept big → net/hr
  online · drops · miles · On dash/Online → plan strip), one **This week** card (net + delta +
  sparkline + `Recap →`, the plan row if one is saved, `NEEDS A LOOK` if the week flagged), one row
  of four equal-height tiles, one footer. Working if: net appears once per scope; `Recap →` opens
  the hub already on THIS week; **On dash ticks live while dashing** (starts at `0s`, never `—`,
  on a fresh dash) and reads the settled `Online` total otherwise; the plan strip re-dims across an
  hour boundary; a day with no dash shows `—`, never `$0.00`/`0s`; Settings is reachable even from
  the permissions-missing and first-run states (the small Settings link under those cards).
  - Confirmed: 0/2
  desk: needs field: inspect the four Home blocks, live On dash tick and hour-boundary plan dimming.
  Triage source: first-line blame 2026-08-12 (`69e72d33b`, original README L796).
