status: active
issues: #1533

- **End a shift by hand from Home (#1533):** when gig.log still shows a shift that the delivery app has already ended, Home shows "End shift…" under the status pill (also on the permission card when a permission is off). Tap it: the sheet names the app and the shift's start, and "Ended at" is prefilled and marked Suggested. The end button stays grey until "I ended this shift in <app>" is on. A time before the start or after now shows one red line and keeps the button grey. Pick a time and end it: the bubble says "Shift ended by hand.", Home's status leaves the shift, the shift's page shows the chosen end time, and the next time you go online in the app a new shift starts. Miles already counted after the real end stay on the ended shift.
  - Confirmed: 0/2
    - desk 11-12 (pull of 11-07 and 11-11, builds `9064004e.dirty` and `96fa4a89.dirty`, DoorDash 8.106.9): data half: the stuck 11-05 shift was ended by hand on 11-06 14:54 (`DASH_STOP source=manual`, end 17:59:00). The UI needs the dev's eyes. Counter unchanged.
