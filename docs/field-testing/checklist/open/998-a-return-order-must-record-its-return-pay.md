status: active
section: §2 Active / 0/2 or 1/2 younger than 6 weeks — newest first
issues: #998, #1127
confirmed: 0/2

- [active] **🆕 NEW — a return order must record its return pay (#998).** Fielded 09-14: an alcohol delivery
  refused at the door became a return order; DoorDash priced the return leg separately (`Return pay
  $10.93`, its own receipt 10 min later) and the row kept only the original base pay. **On-dash:** if a
  delivery is refused or cancelled at the door and DoorDash asks you to return the order, note the
  `Return pay` figure on its breakdown screen and the total on the return's own receipt. **Desk:** the
  dash's `Σ realizedPay` must reach `reportedEarnings`; a delivery row holding only the original base pay
  while the summary is higher by exactly the return pay is the bug.
  - Issue: #998. Confirmed: 0/2
    - desk 09-20: NOT EXERCISED — no refused or returned delivery in the four-day window.
    - desk 09-21: NOT EXERCISED — no refused or returned delivery on the 09-20 dash either.
    - desk 09-26: NOT EXERCISED — the "Can't hand order to customer" page (with its "Return the order to merchant"
      step) rendered twice (09-23 09:21 on 8.98.5; 09-24 16:22 on 8.99.20 — the latter leaks the name, #1127), but
      both drops completed normally and no return happened.
    - desk 09-27: NOT EXERCISED — no refused or returned delivery; Σ realizedPay = reportedEarnings to the cent.
    - desk 09-29: NOT EXERCISED — no refused/returned delivery; Σ realizedPay = reported to the cent on all 7 summary
      sessions ($241.59).
    - desk 10-18: EXERCISED and the money reaches the report. 10-16 session 1034: an alcohol drop that could not be handed
      over became a return (`Can't hand order to customer`, then `Return to store`, `Total return pay: $16.68`). The original
      drop folded at $33.35 (offer pay) and the return leg folded as its own row at $16.68 (`RECEIPT_TOTAL`, from its own
      receipt); Σ = $50.03 = the dash summary. The shape is still unmodelled: the return was minted as a NEW job with a
      null store, no offer and a `DELIVERY_NAV_STARTED` to the store, so the dash counts 2 deliveries for 1 delivery plus 1
      return, and the 10 frames of the return sheet stay UNKNOWN (all masked).
  Triage source: first-line blame 2026-09-15 (`5e1bf3416`, original README L391).
