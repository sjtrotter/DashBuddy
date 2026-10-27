status: field-validated
section: §5 Field-validated / retired
issues: #1141, #1306
confirmed: 2/2

- [field-validated] **Restaurant pickup wait records the real dwell (#1141):** on a restaurant pickup where you tap
  "Arrived at store" and wait, the pull's `pickup_records` dwell for that stop should reflect the real
  wait (minutes, not 0.0), and `PICKUP_ARRIVED` should appear within a few seconds of the
  "Stay in your car / Merchant notified of your arrival" screen.
  - Confirmed: 2/2 (desk 10-18 on `09c70dba`, post-#1306, DoorDash 8.102.6: two restaurant pickups, 10-14 14:36 dwell 1.69 min and 10-16 10:23 dwell 3.23 min; `PICKUP_ARRIVED` lands within 1 s of the first at-store frame each time.)
  - desk 10-11: REFUTED on 8.101.8 (0.01-min dwells, the card had none of the anchors) → #1306 anchors branch 1 on the at-store CARD (not a survey line); re-arm: on a post-#1306 build, restaurant dwell > 0 and `PICKUP_ARRIVED` lands on the first at-store frame (whichever survey copy it shows).
    - desk 10-26 (pull of 10-18 evening to 10-25, build `d807d3f3`): SECOND CONFIRMATION on DoorDash 8.104.3: restaurant dwells are minutes-scale (Panda Express 0.6, 54th Street 3.9, Taco Bell 0.9, Little Caesars 1.4, Jersey Mike's 1.4 min).
  Triage source: first-line blame 2026-10-05 (`5894e98e5`, original README L104).
