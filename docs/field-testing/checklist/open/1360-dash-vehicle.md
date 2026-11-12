status: active
issues: #1360

- 🆕 NEW — Dash vehicle (#1360): the start line names your active vehicle; switching within ten minutes says 'This dash: <name>' and the HUD chip follows; after ten minutes the chip stays on the dash's vehicle and tapping it shows the lock toast; a change from the Garage still says '<name> from your next dash'; the resting odometer comparison shows car and tracked miles even after reading a second vehicle. — Confirmed: 0/2
  - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): data half: 11 `DASH_VEHICLE` START stamps for 11 sessions (Uber included), all on the one vehicle. Switching and the lock were not exercised. Counter unchanged.
  - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): 4 `DASH_VEHICLE` START stamps for 4 shifts, one vehicle; switching not exercised. Counter unchanged.
