status: active
section: §4 1/2 items older than 6 weeks / HUD
issues: #297, #1, #5
confirmed: 1/2

- [active] **Bubble HUD no longer crashes on arrival-bearing dropoffs (#297).** Complete a
  dropoff with an explicit arrival step — a **photo**, **PIN entry**,
  **hand-it-to-customer**, or **alcohol ID-scan** delivery (these fire both
  `DELIVERY_ARRIVED` and `DELIVERY_CONFIRMED`). The bubble must **not** crash, and
  the completed-card stack should show exactly **one** delivery card for that stop
  (no duplicate). This was the fatal `LazyColumn` duplicate-key crash from the
  2026-06-03 session (#297).
  - Confirmed: 1/2. **2026-06-07 (desk review):** 3 arrival-bearing dropoffs fired
    both `DELIVERY_ARRIVED` and `DELIVERY_CONFIRMED` (in fact duplicated — see
    2026-06-07 log #1) and the app had **zero crashes** all day. The dedup held: no
    `LazyColumn` duplicate-key crash. Needs one more clean sighting (ideally
    confirming exactly one card per stop visually in the bubble).
    **2026-06-12 (DoorDash):** crash-free still holds, but the **"exactly one card"**
    sub-claim got a transient counter-example — a Great Greek (hand-to-customer)
    dropoff showed **two** delivery cards while at-door, collapsing to one after the
    paid card. Hypothesis: frozen `completed` Delivery (closed on `DELIVERY_ARRIVED`)
    + the still-`active` Delivery card overlap, keyed `delivery:id` vs `live:delivery:id`
    so no crash but two visible cards. Logged as 2026-06-12 entry #5 for investigation;
    keep watching arrival-bearing dropoffs for the visible duplicate.
  Triage source: first-line blame 2026-06-04 (`a02fcdbcf`, original README L2955). Recorded first confirmation: desk 2026-06-07 (crash half) (existing evidence only).
