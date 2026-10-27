status: active
issues: #810, #818, #700, #1078, #1095

- **🆕 NEW — #810 B1 / PR #818 — JOB_ACCEPT_MISMATCH close tripwire.** A job closing with more accepted
  offers than accounted physical orders now emits one `JOB_ACCEPT_MISMATCH` event + a `StateMachine` WARN
  (the 07-19 session-114 invisible-unassign class is no longer silent).
  **What to watch:** if a dash includes a chat/support-path unassign (no confirmation screen), expect the
  WARN at job close. **Desk-side:** `SELECT * FROM app_events WHERE eventType='JOB_ACCEPT_MISMATCH'` —
  should be non-empty iff an invisible unassign (or the documented #700 suppressed-arrival residual)
  occurred; a normal dash must produce ZERO rows (false-positive watch).
  - Confirmed: 1/2 (2026-07-21 dash, desk 07-22: FALSE-POSITIVE half — zero rows on a normal 6-delivery
    DoorDash day, correct silence. The true-positive half still needs an invisible-unassign dash.
    Desk 07-26: false-positive half re-confirmed — zero rows across three more normal DoorDash
    dashes, 7 deliveries. Desk 07-27: THIRD clean pass — zero rows on 11 deliveries / 12 pickups
    incl. a two-store one-customer job and a receipt stack. The item stays open solely for the
    true-positive half. Desk 07-31, the 07-30 dash: FOURTH clean pass — zero `JOB_ACCEPT_MISMATCH`
    rows across a textbook 4-job dash (6 offers, 4 accepts, 2 declines, 0 timeouts). Still stays
    open solely for the true-positive half.
    Desk 08-09, the 08-01→08-08 window: FIFTH clean pass, and the widest yet — zero
    `JOB_ACCEPT_MISMATCH` rows across five dashes / 26 offers / 15 drops, *including* two shapes
    that would have been prime false-positive bait: a job that absorbed three separately-accepted
    offers (see the 08-09 entry's item 7) and a two-store stack. The tripwire correctly stayed
    silent on both — neither is an accept-vs-orders mismatch, they are pay-attribution defects
    downstream of it. Still stays open solely for the true-positive half.
    Desk 09-09, the 09-08 dash on `8028691a`: **BLIND-IN-PART** — the true-positive shape finally occurred
    (job −444 closed with 1 accepted $21.00 offer / 0 accounted drops, the #1078 recurrence) and the tripwire
    emitted nothing, because `JobCloseEffects.kt:38-45` excludes the `endSession` teardown by design. Filed
    as #1095. The item stays open for the true-positive half on a within-session close.)
  Triage source: first-line blame 2026-07-20 (`022633e47`, original README L1374). Recorded first confirmation: 2026-07-21 dash, desk 2026-07-22 (negative half) (existing evidence only).
