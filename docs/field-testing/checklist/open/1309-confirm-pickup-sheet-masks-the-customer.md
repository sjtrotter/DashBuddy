status: active
issues: #1309

- **🆕 NEW — confirm-pickup sheet masks the customer (#1309).**
  **What to watch:** after a pickup confirmed on 8.101.8, the recognized `pickup_picked_up` /
  `pickup_verify` capture's `customer_name` reads `[redacted:xxxx]` and the hex equals the arrival
  card's for the same customer. **How to tell (desk-verifiable):** pull → grep the capture.
  - Issue: #1309. Confirmed: 0/2
