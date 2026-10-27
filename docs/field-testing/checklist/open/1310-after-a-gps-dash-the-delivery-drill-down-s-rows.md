status: active
section: §2 Active
issues: #1310
confirmed: 0/2

- [active] 🆕 NEW — after a GPS dash, the delivery drill-down's rows carry an arrival odometer and Settings → learned time constants report learned constants once ≥10 eligible single-order deliveries exist (#1310) — Confirmed: 0/2
  - desk 10-18: data half holds: after the v15 refold, 269 of 281 `delivery_records` rows carry `odometerAtArrival` (13 of the 15 new drops; the 2 without are the drops with no `DELIVERY_ARRIVED`). The Settings half needs dev eyes.
