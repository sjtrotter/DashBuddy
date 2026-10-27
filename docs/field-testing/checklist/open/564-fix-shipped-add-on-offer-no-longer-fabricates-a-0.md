status: active
issues: #564, #570

- **🔧 FIX SHIPPED — add-on offer no longer fabricates a $0 "completion" (#564, PR #570). CONFIRM ON DASH.**
  06-21 seq98: accepting a **mid-stack add-on** offer (a new order added while a pickup was in
  flight) misrecognized the offer's transient frame as a delivery summary and logged a **fake $0,
  customer-less completion** of the not-yet-picked-up store — corrupting earnings. Fixed two ways
  (recognition rejects offer markers; the state machine only completes a task that reached the
  **dropoff**). **Confirm on dash: 0/2 —** when you **accept an add-on/stacked offer while still at
  or heading to a store** (esp. a "High paying offer!" add-on), watch that **no** bogus completion
  pops (no "$0.00" delivery, no premature "delivered" for a store you haven't dropped), the earnings
  total **doesn't jump then stay wrong**, and the original pickup keeps its identity (no duplicate
  store task). If a phantom completion still appears, note the add-on's store + the store you were
  working, and grab the capture sequence around the accept.
  Triage source: first-line blame 2026-06-22 (`6fba7dfee`, original README L2345).
