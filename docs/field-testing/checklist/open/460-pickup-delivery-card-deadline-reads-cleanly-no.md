status: active
issues: #460

- **Pickup/Delivery card deadline reads cleanly — no double "by" (#460).**
  The deadline caption read `till pickup-by · by 17:10` (two "by"s); now `till pickup · by 17:10`
  / `till deliver · by 17:10`. Desk- or dash-verifiable on any pickup/delivery card. (The
  separate pickup/delivery card visual-parity redesign stays tracked in #460.)
  - Confirmed: 1/2. **2026-06-14 (DoorDash):** caption reads **fully fixed/different from before** — no
    double "by". One more to validate.
  Triage source: first-line blame 2026-06-13 (`92ef59a43`, original README L2620). Recorded first confirmation: 2026-06-14 (existing evidence only).
