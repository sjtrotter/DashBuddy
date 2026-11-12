status: active
issues: #1388

- Receipt vehicle (#1388): log Gas and Car care from the bubble — the chip shows the active car; pick the other car for one entry, log it, and the HUD chip still shows the active car; the log row names the car (with two or more cars); expenses.csv and fuel.csv carry vehicle_id/vehicle_label. — Confirmed: 0/2
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): 2 `FUEL_FILLUP` events carry `vehicleId`; the UI needs the dev's eyes. Counter unchanged.
