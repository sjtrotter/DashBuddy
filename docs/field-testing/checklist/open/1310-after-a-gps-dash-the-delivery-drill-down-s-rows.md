status: active
issues: #1310

- 🆕 NEW — after a GPS dash, the delivery drill-down's rows carry an arrival odometer and Settings → learned time constants report learned constants once ≥10 eligible single-order deliveries exist (#1310) — Confirmed: 0/2
  - desk 10-18: data half holds: after the v15 refold, 269 of 281 `delivery_records` rows carry `odometerAtArrival` (13 of the 15 new drops; the 2 without are the drops with no `DELIVERY_ARRIVED`). The Settings half needs dev eyes.
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): data half: 12 of 12 new rows carry `odometerAtArrival`. Counter unchanged.
