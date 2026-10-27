status: active
issues: #1103, #1111

- **🆕 NEW — a dash you start while the last dash's receipt is still on screen must not inherit its
  money.** Fielded twice on 09-10/09-11: the previous dash's `delivery_summary_collapsed` sheet was
  still up 14 ms (and 251 ms) after `DASH_START`, the settle gate parked its `sessionEarnings` read
  inside the NEW session, and a `SESSION_PAY_SETTLE` three seconds later committed it — session 483
  recorded `reportedEarnings = $40.14` on a two-minute dash with zero deliveries. The task/job
  lifecycle was completely inert (no completion, no re-price, no bubble), so this is a
  session-total-only defect. **On-dash (deliberate):** finish a delivery, leave the receipt sheet on
  screen, tap **Dash Now** without dismissing it, then go Offline within a minute or two without
  taking an order. **Desk:** that session's `reportedEarnings` must be NULL (it had no deliveries) —
  a figure equal to the PREVIOUS dash's total is the bug. Also check for a duplicate
  `Delivery - <amt>.png` / `DeliveryBreakdown - <amt>.png` screenshot pair timestamped inside the new
  dash, and confirm no `DELIVERY_RECEIPT_REPRICE` is attributed to the new session.
  - Issue: #1103. Confirmed: 0/2
    - **fix landed (PR #1111, #1103):** the receipt's wheel is admitted to the settle gate only
      while this session owns the receipt's job (`activeJob` live, or `lastClosedJobReceipt` set —
      both cleared by `endSession`). A refused read prints `settle gate: receipt running-total read
      refused` at DEBUG under the `StateMachine` tag; on the repro above that line IS the fix firing,
      so grep for it alongside the NULL `reportedEarnings`.
    - desk 09-15: no-regression — all three `early_offline` sessions report NULL and exactly one `SESSION_PAY_SETTLE` fired in three days (inside its own session). The causing shape (a receipt sheet up at `DASH_START`) did not recur, so this is a clean negative, not a repro.
    - desk 09-20 (first pull WITH PR #1111 on the device): UNEXERCISED, clean negative — `settle gate: receipt running-total read refused` = 0 hits (the causing shape did not recur, and no FALSE refusal either); all five `early_offline` zero-delivery sessions report NULL, including 646 which started 3 min 3 s after session 630 closed on $156.34; six `SESSION_PAY_SETTLE` timers fired, each inside its own session committing that session's own figure; no duplicate `Delivery - <amt>.png` pair straddles a dash boundary and no `DELIVERY_RECEIPT_REPRICE` is attributed to a following session.
    - desk 09-21: UNEXERCISED, clean negative a second time — `settle gate: receipt running-total
      read refused` = 0 hits (causing shape absent, and no FALSE refusal); both `early_offline`
      zero-delivery sessions (660, 685) report NULL `reportedEarnings`; no `Delivery - <amt>.png` /
      `DeliveryBreakdown - <amt>.png` pair straddles a dash boundary; no `DELIVERY_RECEIPT_REPRICE`
      is attributed to a following session. Only the deliberate on-dash repro can move this.
    - desk 09-26: UNEXERCISED, clean negative (3rd) — 0 "receipt running-total read refused"; all three zero-drop
      sessions (718, 732, 736) report NULL; the 3 `SESSION_PAY_SETTLE` fires were each inside their own session;
      back-to-back starts 707→714 (38 s) and 746→750 (20 s) each carry only their own total.
    - desk 09-27: UNEXERCISED, clean negative (4th) — 0 "receipt running-total read refused"; the dash started 10:43
      from `waiting_for_offer` (no receipt on screen); 5 `SESSION_PAY_SETTLE` fires all inside session 754;
      reportedEarnings = the dash's own $209.22.
    - desk 09-29: UNEXERCISED, clean negative (5th) — 0 "receipt running-total read refused"; the four zero-drop
      sessions (789, 802, 803, 811) report NULL; back-to-back starts 776→785 (26 s after a dash_summary) and 785→789
      carry only their own totals.
  Triage source: first-line blame 2026-09-13 (`bd4c8a71b`, original README L492).
