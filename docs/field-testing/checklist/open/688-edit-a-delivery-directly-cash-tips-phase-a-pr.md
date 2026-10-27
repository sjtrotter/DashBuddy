status: review
issues: #688

- **🆕 NEW — edit a delivery directly + cash tips (#688 phase A / PR).** In **Analytics → a dash →
  the delivery drill-down**, **tap a delivery row** (or its pencil) → the **Adjust delivery** dialog:
  Store name / Pay / Tip / Cash tip / Miles / Note. This replaces the pay-only editor and is the real
  fix for the 07-05 "bill millers" workaround (editing the store name into a note).
  How to tell it's working: (1) **Edit a store name directly** — change a drop's store to the correct
  merchant, Save; the row's store name updates and the per-store breakdown (Money tab → Top stores)
  re-buckets it under the corrected name. The "est. offer pay" qualifier on an estimate row must
  **survive a store-name-only edit** (a non-pay edit must NOT flip it to a corrected/plain row). (2)
  **Add a cash tip** to a delivery (or on Add-missed-delivery): the dash header gains a separate
  **"+$X cash tips"** line, the row shows a **"+$X cash"** line and its net rises by the cash — BUT the
  **"Gross (reported)" tile and the unattributed callout do NOT shrink** (cash adds to gross/net, it is
  NOT reconciled against reported). (3) Re-price a captured drop's Pay: net recomputes and (on a
  machine row) the basis reads corrected; a MANUAL row stays MANUAL. (4) Nothing is destroyed — the
  original event stays; a projector rebuild reproduces the edited rows. **Phase B (per-leg mileage) is
  deferred.** (#688 phase A / PR)
  - Confirmed: 0/2
  desk: needs field: exercise store/pay/cash-tip edits and inspect the projected rows and preserved events; the dialog and qualifier checks need eyes.
  Triage source: first-line blame 2026-07-06 (`164282ba4`, original README L1749).
