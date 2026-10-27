status: active
issues: #1127, #1128, #1139, #1022

- **8.99.20 privacy rules (#1127 / #1128 / #1139) + the store-signature block (#1022):** on the next dash, if any of
  these surfaces appears — the "Can't hand order to customer" steps page, the pickup "What do you want to do next?"
  sheet, the stacked-job "Select an Order" picker, or a return-order store-employee signature pad — the pull should
  show the first three as RECOGNIZED envelopes (`dropoff_customer_unavailable`, `pickup_resolution_options`,
  `pickup_order_picker`) with the customer name masked (`Contact [redacted:xxxx]` / `For [redacted:xxxx]`) and NO
  envelope at all for the signature pad (sensitive, dropped at the gate). Any of them landing in `UNKNOWN/` with a raw
  name is the thing to report.
  - Confirmed: 0/2
  Triage source: first-line blame 2026-10-05 (`967946055`, original README L133).
