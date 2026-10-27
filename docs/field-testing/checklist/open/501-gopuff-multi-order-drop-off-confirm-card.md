status: review
issues: #501, #743

- **🆕 NEW — GoPuff / multi-order drop-off confirm card recognized (#501 items 1-2 / PR #743).**
  The "Confirm you have the correct order before drop-off / Mix-ups frequently occur…" card that
  appears before a drop on a **multi-order Dash** (GoPuff Drive batches AND ordinary multi-merchant
  grocery batches) is now a recognized screen (`dropoff_multi_order_confirm`) instead of falling to
  UNKNOWN. It's recognize-only (no state change) — its only job is to stop hitting the UNKNOWN
  capture folder. **How to tell it's working (desk-side, after a multi-order dash):** the
  `Confirm…correct order before drop-off` frames should NO LONGER appear under
  `captures/.../accessibility.window/UNKNOWN/`; they should sort into
  `accessibility.window/dropoff_multi_order_confirm/`, and the redacted capture must show the
  customer name line masked (`[redacted:...]`), with the store name + item count kept. Completes
  #501 (all 3 items). This is the last recognition piece of #501; watch that it doesn't perturb the
  dropoff flow (no phantom re-mint around the confirm card).
  - Confirmed: 0/2
  desk: desk-verifiable: inspect multi-order confirm captures for the recognized class and absence of state effects.
  Triage source: first-line blame 2026-07-10 (`7d753a904`, original README L1628).
