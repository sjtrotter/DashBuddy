status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #301, #1128, #810
confirmed: 0/2

- [active] **🆕 NEW — unassigning at the store must not record a pickup (#301).** **On-dash (deliberate):** if you
  ever have to unassign at a store, take the `Help → I have an issue → Unassign order` path. **Desk:**
  one `TASK_UNASSIGNED`, no `PICKUP_CONFIRMED` for that task, no `pickup_records` dwell row, and the
  accepted offer resolved rather than left an orphan (09-15 session 568 recorded a phantom
  `PICKUP_CONFIRMED` with a 17.4-min dwell and an orphan accept).
  - Issue: #301. Confirmed: 0/2
    - desk 09-20: NOT EXERCISED — zero `TASK_UNASSIGNED` in the window.
    - desk 09-21: NEARLY exercised, still not a test — the dev reached `pickup_select_issue` →
      `pickup_resolution_options` at 12:38:53 (the sheet offering "Unassign with no pay" /
      "Contact support") and took **Contact support**. Zero `TASK_UNASSIGNED`, zero phantom
      `PICKUP_CONFIRMED`. Next time the situation arises, take the Unassign path.
    - desk 09-26: NOT the deliberate path, but the same defect class fired twice via SUPPORT-CHAT removal — jobs
      …-733 (09-23 15:14, after a "Missed Delivery" push) and …-737 (09-23 16:03, a new offer at the store) each
      recorded a phantom `PICKUP_CONFIRMED` plus a `pickup_records` dwell row (16.1 / 14.5 min), committed by a 10 s
      `GRACE_COMMIT`, with zero `TASK_UNASSIGNED`; offer d58fc055 left unlinked. The 8.99.20 "What do you want to do
      next?" sheet (`Unassign with no pay` / `Order not found in system`) is UNKNOWN and leaks a name (#1128).
      Comment on #301.
    - desk 09-27: NOT EXERCISED — 0 `TASK_UNASSIGNED`, 7 `PICKUP_CONFIRMED` = 7 real jobs, no support removal.
    - desk 09-29: NOT the deliberate path; the support-CHAT removal fired a 3rd time (09-27 16:10, session 790). 0
      `TASK_UNASSIGNED`; no phantom PICKUP_CONFIRMED this time because the next accept (a different H-E-B) REUSED
      the abandoned task …-793 (#810 shape): PICKUP_ARRIVED re-emitted with the first store's arrival, the real
      16:25:10 arrival lost, dwell 55.5 vs ~39.9 min; offer d3c03872 left ACCEPTED → JOB_ACCEPT_MISMATCH
      inconclusive. New anchor: DoorDash's own "You've been waiting a while — would you like to unassign?" screen
      (UNKNOWN 09-27 11:59:34, `order_self_unassign_*` ids). Comment on #301. 0/2.
  Triage source: first-line blame 2026-09-15 (`5e1bf3416`, original README L428).
