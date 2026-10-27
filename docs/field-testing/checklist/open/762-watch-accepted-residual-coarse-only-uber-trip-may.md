status: review
issues: #762, #784

- **🆕 NEW — WATCH (accepted residual): coarse-only Uber trip may leave its job open past the receipt (#762 D2 / PR #784).**
  A marker-less `on_job_view` frame between the post-trip receipt and idle walks the flow
  PostTask→TaskActive→Idle and suppresses the receipt-exit job close when NO leg screen ever activated
  a task (coarse-only trip). Chosen fail direction is absorption (job stays open; closes at session end)
  — this item quantifies whether the frame shape actually occurs in the field.
  **How to tell (desk-side):** after an Uber dash, any job whose deliveries completed but whose job-close
  event only arrived at `DASH_STOP`; if seen, capture the post-trip → idle frame sequence.
  - Confirmed: 0/2
  - (2026-07-19: N/A — no job existed)
  desk: needs field: a coarse-only Uber receipt-to-idle sequence must occur to measure whether its job remains open.
  Triage source: first-line blame 2026-07-15 (`19991604a`, original README L1528).
