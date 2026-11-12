status: active
issues: #1314

- **🆕 NEW — gift order drop-off sheet recognized + masked (#1314).** On a gift delivery,
  the arrival sheet shows as `dropoff_pre_arrival`, `DELIVERY_ARRIVED` lands, and the capture's
  `gift_recipient_name` and `user_name` read `[redacted:xxxx]`. — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): not exercised. Counter unchanged.
