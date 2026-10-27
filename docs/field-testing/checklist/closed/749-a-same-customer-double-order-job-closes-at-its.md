status: field-validated
section: §5 Field-validated / retired
issues: #749, #996
confirmed: 2/2

- [field-validated] **🆕 NEW — a same-customer double-order job closes at its receipt; the next offer is its OWN job (#749).**
  A job where **both orders go to the same customer** (the offer card literally says so — e.g. Willie's +
  Sonic to one person) mints two dropoff placeholders but only ONE physical drop. Before #749 that leftover
  placeholder kept the job "open" forever, so the **next offer folded into the finished job** and its pay
  showed up as **unattributed** (the job-61 class — $19 of $45.75 swallowed). The fix proves completion from
  the pickup side (`JobCompleteness` per-customer coverage arm).
  **How to tell it's working (needs a same-customer multi-order offer, then a NEXT offer after it):** after
  the single drop completes, the job **closes** (the bubble/HUD returns to idle/waiting, not a lingering
  active task); the **next offer you accept starts a fresh job** (its own store/economics on the card, not
  appended to the finished one); in the Money-tab drill-down the two orders appear under **their own dashes/
  rows** and there's **no unattributed-pay spike** for that stretch. Watch especially the hand-off between a
  same-customer double and the very next accept.
  - Confirmed: 2/2 (desk 07-27: the exact shape fielded on 07-26 — Dunkin' + Taco Palenque, two
    orders one customer, TWO pickups ONE drop. Job closed at its $9.80 receipt, the next offer
    minted its OWN job (13:55:52), session reconciled $80.55 == $80.55 with zero unattributed —
    the job-61 class did not recur. Caveat: it closed via the STRICT completeness arm (no
    "#749 coverage arm closed" line), so #749's own per-customer arm is outcome-validated but not
    path-exercised; the second confirmation should ideally catch the coverage-arm path.)
  - **BROKEN-IN-PART (desk 08-09, the 08-07 dash) — the shape recurred and CLOSURE worked, but
    half the job's pay vanished.** Offer seq 1496, $13.10, two orders to one customer: both pickups
    confirmed, one physical drop, the job closed correctly and the next offer minted its own job —
    #749's own deliverable held. But the single folded delivery took `offerPayShare = 6.55`, i.e.
    the offer total split across BOTH owed drops while only one drop ever exists to claim a share,
    and the session's unattributed remainder is exactly $6.55. #749 fixed *closure* for this shape;
    it did not touch the `owedDropoffs` denominator that prices it. Filed as **#996** and written up
    as item 6 of the 2026-08-09 entry below (per this file's own rule, a broken finding goes to the
    log immediately). The item stays here at 1/2 for the closure half's second confirmation — but
    read it alongside #996, because on a receipt-less job the two halves disagree.
  - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): SECOND CONFIRMATION, and the coverage-arm path itself: 10-23 17:48:08 `#749 coverage arm closed job … (pickups=2 dropoffs=2)` on a Panda Express + 54th Street "Drop off 2 orders" job. One delivery row with the full $10.00 (no half-split, so the #996 pay half held too), and the next accept minted its own job.
  Triage source: first-line blame 2026-07-11 (`c0a5f966f`, original README L1561). Recorded first confirmation: 2026-07-26 dash, desk 2026-07-27 (existing evidence only).
