status: active
section: §2 Active / Re-aged by whole-item blame (a sub-line note after 2026-08-25 counts as touched) — newest first
issues: #752, #757, #736
confirmed: 0/2

- [active] **🆕 NEW — unassign an order AFTER pickup (dropoff phase) also produces NO paid artifact (#752 / PR #757).**
  Companion to #736: when the unassign happens while a **dropoff** is active (or was just grace-retired
  en route to the customer — e.g. a help/idle screen interrupted the drive, the retire grace fired,
  then you unassigned), the app now retro-marks **that drop** as unassigned instead of a sibling
  pickup, so the close-out can't fabricate a `DELIVERY_COMPLETED` for an order you never delivered.
  On the previous build this dropoff-phase cross-frame shape fabricated a paid delivery and suppressed
  the pickup's legitimate confirmation.
  **How to tell it's working (on-dash + desk-side):** unassign an order you've already picked up (mid
  drive to the customer). Expect the **"Unassigned: <store>" bubble**, the card clears, and the next
  offer works normally. Desk-side, the exported log / `app_events` should show **exactly one
  `TASK_UNASSIGNED`** (phase DROPOFF) for that order and **no `DELIVERY_COMPLETED`** for it — no phantom
  "$0 PAID" delivery in the Money tab. (In the cross-frame shape a `DELIVERY_CONFIRMED` from the earlier
  grace retire may already have fired on the prior frame — that's read-model row-inert and expected, so
  don't treat its presence as a failure.) The sibling pickup of a stacked job should still show its
  normal `PICKUP_CONFIRMED`.
  - Confirmed: 0/2
    - desk 09-05: NOT this item's case — the slice's one `TASK_UNASSIGNED` (seq 1806) was
      PICKUP-phase (arrived at the store, never confirmed); it behaved correctly (the $45.45 quote
      stayed unattributed, no paid artifact), but the dropoff-phase retro-mark is still unexercised.
  Triage source: first-line blame 2026-07-11 (`31f0f6905`, original README L1608).
