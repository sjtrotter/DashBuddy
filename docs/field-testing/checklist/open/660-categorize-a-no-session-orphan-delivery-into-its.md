status: review
issues: #660

- **🆕 NEW — categorize a "(No session)" orphan delivery into its real dash (#660 piece 2).**
  The Money-tab "(No session): $X across N deliveries" callout is now **tappable** — it opens an
  orphan list; tapping a delivery opens a session picker (ended dashes within ±48 h of the drop, same
  platform, nearest first). Confirming assigns the orphan to that dash. A `DELIVERY_SESSION_ASSIGN`
  correction is written; the projector re-attributes the row (attribution ONLY — pay/net are never
  re-priced) and the read-model refreshes reactively.
  **How to tell it's working (needs a real orphan on-device — a mid-dash service/app restart that
  dropped a delivery's `sessionId` while the dash summary still captured its pay):** the callout shows
  a nonzero "(No session)" amount; tapping it lists the orphan(s); picking the correct dash makes the
  **callout shrink by that delivery's pay** (and empty entirely once all orphans are categorized) with
  no manual refresh; the target dash's **drill-down gains a row tagged "assigned by you"** and its
  **header delivery count goes up by one** (header and list agree — no mismatch); and if the orphan's
  dollars were already inside that dash's reported summary, the Money-tab **gross drops** (the
  double-count heals) rather than staying inflated. Undo path: open the assigned row's Adjust dialog →
  **"Remove from this dash"** returns it to the bucket. Frozen economics must be untouched by all of
  this (the row's net/pay/est-offer-pay disclosure are the same before and after). PR for #660 piece 2.
  - Confirmed: 0/2
  desk: needs field: assign an orphan delivery through the session picker, then inspect DELIVERY_SESSION_ASSIGN and re-attribution without pay changes.
  Triage source: first-line blame 2026-07-11 (`7ef8df546`, original README L1590).
