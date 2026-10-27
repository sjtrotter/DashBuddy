status: review
issues: #501, #738

- **🆕 NEW — GoPuff zone-arrival screens recognized (recognize-only, no state effect) (#501 item 3 / PR #738).**
  The GoPuff "Navigate to zone" / "Arrived at store" screens (the `go_to_store_action_view` CTA card)
  are now recognized as `pickup_zone_arrival` instead of landing in UNKNOWN. **How to tell it's working
  on a GoPuff (DoorDash Drive) run:** after the dash, the zone-arrival frames should NOT appear in the
  UNKNOWN capture folder (they were ~15 frames/session of UNKNOWN noise on 06-14), AND the bin-scan
  "Pickup steps" screen must still be the one-and-only `PICKUP_ARRIVED` anchor (exactly one arrival per
  warehouse visit — the new rule is recognize-only and must not steal or duplicate it). A zone-arrival
  frame still hitting UNKNOWN, or a second/missing pickup arrival, is a regression. Rule was built from
  hand-cited anchors (the 06-14 captures were never committed), so a live GoPuff sighting doubles as the
  first real-frame validation — grab a capture either way. **Also spot-check the captures LABELED
  `pickup_zone_arrival` are actually zone screens** (the anchors are shared with regular pickups; a
  regular pre-arrival or map frame tagged as zone-arrival is the over-match regression, not a pass).
  - Confirmed: 0/2
  desk: desk-verifiable: inspect GoPuff zone-arrival captures and confirm recognize-only handling adds no state transition.
  Triage source: first-line blame 2026-07-09 (`602f7ff40`, original README L1674).
