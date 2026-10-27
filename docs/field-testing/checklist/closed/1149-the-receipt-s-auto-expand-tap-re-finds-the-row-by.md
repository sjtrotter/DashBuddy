status: field-validated
issues: #1149, #1102

- **🆕 NEW — the receipt's auto-expand tap re-finds the row by its labels, and every tap lands on the
  control that OWNS the click (#1149).** Taps now resolve the clickable owner first (verified, then
  `refresh()`ed right before the click), and an id-less bind is re-found by its exact label
  fingerprint BEFORE its captured bounds — so a receipt sheet still sliding when it was bound no longer
  makes the first tap miss. **No dash needed on the expand path** (any completed delivery's receipt).
  **How to tell it works:**
  1. The settled receipt's `EXPAND_EARNINGS` tap lands on the FIRST admitted settled frame:
     `Performing expand_earnings` → `Single verified candidate` with no `Could not find any live node`
     before it (the #1102 retry should no longer be needed).
  2. No new `Strict click: refusing`, `(N stale)`, `semantic twins` or `semantic re-find inconclusive`
     WARNs on known-good surfaces (the receipt, the heads-up Accept/Decline buttons).
  3. The `bindShortfall{…}` census in `PipelineStats` is unchanged from the previous pull.
  Confirm-decline automation stays DENIED by the dev's choice — it is not a validation target here.
  - Issue: #1149. Confirmed: 2/2 (desk 2026-10-05: 13 expands, 12 clean, 1 stale abort while an offer card covered the receipt — the designed outcome)
    - desk 10-18: criterion 1 holds (7 of 7 `expand_earnings` taps land with `Single verified candidate` on the first settled frame, no `Could not find any live node`); 29 heads-up Decline taps resolve cleanly. Criterion 2 broke once: the only heads-up ACCEPT in the pull (10-16 10:21:09) logged `Semantic re-find for accept_offer ... inconclusive (1 hit(s), incomplete) — aborting to manual`; the dasher accepted by hand 5 s later. The tap fired while the bubble activity was being torn down. Counter unchanged.
    - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): SECOND CONFIRMATION. Criterion 1: 4 of 4 `expand_earnings` taps land `Single verified candidate` on the first settled frame (10-20, 10-21, 10-25 ×2, incl. DoorDash 8.104.3). Criterion 2: the one heads-up Accept (10-20 14:50:48) and all 26 heads-up Declines resolve cleanly; no `stale`, `inconclusive` or `twins` WARN, so the 10-18 teardown abort did not recur. Criterion 3: no `bindShortfall`, `bindUnprovable` or `bindRefused` entries.
  Why proposed: Live tap gate: receipt expansion must resolve the owning control and refuse stale or ambiguous targets.
  Triage source: first-line blame 2026-09-29 (`6becba8f8`, original README L300).
