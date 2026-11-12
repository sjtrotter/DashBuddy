status: active
issues: #1599, #1326

- **A saved receipt is matched to its deliveries (#1599):** after a shift with a stacked delivery or a job that took
  two offers, the shift page shows "Receipt $X · <time> · not matched" under the money check, also when the shift's
  totals agree. Match opens "Which deliveries did this receipt pay for?" with the receipt's delivery (and, on a
  one-offer stack, its other drop) ticked and marked Suggested, and "Delivery pay $A to $B" under the list. Confirm:
  the line goes, each ticked delivery reads "Matched by you" with an even split, and the stack's other receipt line
  goes too. Not sure, Back or a swipe saves nothing and the line stays. Undo match on the job sheet puts the old pay
  back and the line returns. Desk: the `#1599 matched receipts` queries in `desk-validation-playbook.md`. Broken = a
  delivery paid by two receipts, a save without Confirm, a matched row re-priced later, or shares that do not add up
  to the receipt.
  — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): not on the phone. Counter unchanged.
