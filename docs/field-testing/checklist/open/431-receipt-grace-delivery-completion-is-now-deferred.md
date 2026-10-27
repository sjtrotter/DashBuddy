status: active
section: §2 Active / Counter unspecified — retained active, newest first
issues: #431, #456, #466
confirmed: 2/2

- [active] **Receipt grace — delivery completion is now deferred ~2.5s (#431 pt 2).**
  The delivery-summary (receipt) screen no longer retires the task instantly; it arms a short
  authoritative grace exactly like the dash summary. Watch: (a) the "Saved: $X" receipt bubble
  fires exactly ONCE per delivery (the expanded re-observation used to be able to double-fire
  it); (b) stacked orders still split cleanly — receipt → next pickup must show the new task
  immediately with the old one logged; (c) a receipt that flashes mid-dropoff (misrecognition)
  no longer kills the live task — the task card should survive. Broken = double "Saved" bubble,
  a delivery missing from the log, or the bubble's task card stuck on the finished delivery
  well past ~3s after the receipt.
  - Confirmed: **2/2 for sub-case (a)** — once-per-delivery receipt — **VALIDATED** (moved to the
    2026-06-14 entry). **2026-06-12:** two deliveries → one "Saved" each. **2026-06-14 (DoorDash):** no
    double "Saved" receipt anywhere across the dash. ⏳ **Still open:** (b) stacked-order
    receipt→next-pickup split and (c) receipt-flash-mid-dropoff survival weren't exercised on either
    dash — keep watching those two on the next stacked/edge dash. (The `$`-sign bug noted here on 06-12
    is fixed — #456/#466, confirmed 2026-06-14.)
  Triage source: first-line blame 2026-06-12 (`d8dad2ef9`, original README L2700).
