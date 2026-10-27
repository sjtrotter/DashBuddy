## 2026-10-18 (desk analysis of the 10-18 pull: dashes on 10-14, 10-16, 10-17 and 10-18; money reconciles on 7 of 8 summary dashes; a boost badge hides a whole offer)

**Date:** 2026-10-18 (pull) · **Platform(s) tested:** DoorDash 8.102.6 (every dash; first seen 10-14 14:23); Uber opened on 10-15 without a trip · **Branch under test:** `master` at `09c70dba` (post-#1341 merge), installed 10-12 20:50; every dash in the window ran on it (`app=0.230.0+09c70dba`, no `.dirty`) · **Field conditions:** 13 sessions: 8 ended on the dash summary (10-14 ×1, 10-16 ×3, 10-17 ×3, 10-18 ×1) and 5 short `early_offline` re-dashes with only declines. 15 delivery rows, 49 offers received, 12 accepted. 10-12, 10-13 and 10-15 had no dash. Two process restarts while offline (10-15 15:00, 10-16 21:27), both with a clean `Recovery complete`. Pull dir `~/dashbuddy/logs/2026/10/18` (1374 captures, 20 rotations, DB with WAL); device purged and verified at 0 captures.

### Bugs
1. **P1: a "Very busy" boost badge makes a complete offer card UNKNOWN; one accepted offer was never recorded** (10-17 15:40:13).
   The card carried a standalone `+$1.50` node beside `Very busy`. `offer_popup` branch 1 rejects any `^\+\$N.NN$` node
   (the #1121 add-on guard added 10-08), branch 2 needs the paired `+N stops` line, and `side_nav_drawer` gives up on the
   same signature, so the frame classified UNKNOWN (`UNKNOWN/2026-10-17_15-40-13-095…ffbf4f`). The badge was on screen from
   the first frame, so no `OFFER_RECEIVED` was ever written; the job was minted from `pickup_pre_arrival` at 15:40:32.
   No receipt was shown before End dash, so the drop folded with pay basis `NONE` (net −$3.69) and the dash's $37.07
   sits in "reported but not matched" (`#691 offer-pay estimate eligible but unsplit` WARN at 16:59:06). The same badge hid
   the 17:00:35 frame of a second card, which survived only because its first frame (17:00:34) rendered before the badge.
   Likely cause: the delta reject pattern cannot tell the boost chip from an add-on delta. One possibility is to scope it
   to the card body or to require the paired stop line, which would need a fixture of each.
   - **Status:** Open (new issue candidate).
2. **P2: an accept recorded as `OFFER_TIMEOUT`, #1119 third sighting** (10-17 18:37:03). Offer `dd8147f0` ($37.26)
   presented 18:36:51; the next frames were `side_nav_drawer` and `waiting_for_offer` (18:37:03), which resolved the offer
   as a timeout; `pickup_pre_arrival` followed at 18:37:05. The drop priced from its receipt (`DROP_SHARE` $37.26, equal
   to the summary), but the session shows 0 accepts against DoorDash's 1, and the job has no offer link.
   - **Status:** Open (#1119; evidence comment candidate).
3. **P2: a return-to-store order folds as a second delivery on a new job** (10-16 14:31 to 14:54, session 1034). An alcohol
   drop could not be handed over; DoorDash converted it to a return (`Total return pay: $16.68`). The return leg was minted
   as a new job with a null store and no offer, with `DELIVERY_NAV_STARTED`, `DELIVERY_ARRIVED` and `DELIVERY_COMPLETED`
   at the store. Money is right this time ($33.35 + $16.68 = $50.03 = summary), so this is a shape defect: the dash counts
   two deliveries, and the store column reads empty. The return sheets (10 frames) and the customer-unavailable pages are
   UNKNOWN, all masked.
   - **Status:** Open (#998; evidence comment candidate).
4. **P3: the only heads-up ACCEPT tap aborted to manual** (10-16 10:21:09). `Semantic re-find for accept_offer ...
   inconclusive (1 hit(s), incomplete) — aborting to manual (#1149)`. The bubble activity stopped and destroyed in the same
   250 ms (`bubble notification removed (deleteIntent)` at 10:21:09.902). One possibility: the window list was enumerated
   mid-teardown, so the deciding window read as incomplete. The dasher accepted by hand at 10:21:15. All 29 heads-up
   Decline taps resolved on `Single verified candidate`.
   - **Status:** Open (new issue candidate, or a #1149 comment; one sighting).
5. **P3: two drops have no `DELIVERY_ARRIVED`.** 10-14 14:26:18, a `dropoff_handoff` frame after `dropoff_workflow_sheet`
   wrote no arrival; 10-17 18:20:33, the drop went `dropoff_pre_arrival`, the `complete_delivery` tap, then
   `dropoff_step_instructions` and the camera, with no `dropoff_photo` or `dropoff_handoff` frame. Both rows carry
   `LEGACY_SPAN` mileage and no `odometerAtArrival`; the second completed only when the dash ended (the #1078 absorbed
   retire, completion stamped 18:21:34). Would need to confirm from the reducer log why the 10-14 handoff frame did not
   stamp the arrival.
   - **Status:** Open.

### Field UX context
- DoorDash 8.102.6 Compose offer cards now carry a boost row (`Very busy` + `+$1.50`) and a `[icon] High pay for Platinum`
  badge. Without the `+$` node the badge is harmless: 3 recognized `offer_popup` envelopes carry `Very busy`.
- 10-18 08:36: the second offer of the run (`b8c16588`) got two heads-up Decline taps; the DoorDash confirm sheet appeared
  after each and the card came back between them. It resolved `OFFER_TIMEOUT` on `waiting_for_offer` 17 s after the last
  sheet sighting. **Dev answer (10-18):** the first decline was backed out on purpose to read what the acceptance rate
  would fall to; the dev then flipped the sheet's "Pause offers from this store" switch and declined after that. So this
  is a real decline recorded as a timeout. The trace: offer presented 08:36:24 with a countdown ending about 08:37:05;
  second Decline tap 08:36:47.6; confirm sheet recognized 08:36:48.2 in its own window (1106, `BottomSheetModal`); the
  sheet window lost active status at 08:36:51.5 and a `side_nav_drawer` frame was admitted, so the classifier's last
  screen target moved off the sheet; a 33 %-area type-2 window sat in front from 08:36:56 to 08:37:02 (the pause flow,
  likely); the final tap at 08:37:03.7 classified `UNKNOWN click` (not captured, so its label is unknown); the exit frame
  landed 08:37:05.3. Two rules refused the decline, each on its own: the exit was 17.1 s after the last sheet sighting
  (`declineSheetWindowMs` 15 s), and it landed at the card's own countdown end, which `EffectMap` reads as ran out.
  Hypotheses: (a) the pause-store switch changes the sheet (or opens a second sheet) so the sheet sighting is not
  refreshed while the dasher is still on the decline path; (b) a final `Decline offer` tap near the countdown end cannot
  be told from an expiry without the click envelope, and that click fell UNKNOWN because `screenIs` no longer held the
  sheet. Would need a capture of the sheet after the switch is flipped, and the click envelope of the final tap, to
  confirm either. Related: #1104 (click-vs-window race), #1119 (accept-as-timeout, the mirror case).
- **Why the pause flow (and, the dev reports, the offer card's shopping list) never lands in UNKNOWN (desk check
  10-18).** From 08:36:52 to 08:37:01 the pipeline took a tree snapshot of the main window every few hundred ms
  (98 to 100 nodes) and admitted none of them: no `Captured screen` line, so the frame gate dropped each as a duplicate
  of the last admitted screen, `side_nav_drawer` (08:36:51.58). The drawer rule's anchors are the
  `side_nav_content_container` id plus the words ratings, promos, preferences, and those nodes sit in the main
  activity's tree whether the drawer is open or closed: every `offer_popup` (41/41), `idle_map` (31/31),
  `waiting_for_offer` (26/26) and `on_dash_map` (4/4) capture in this pull satisfies the drawer rule's `require`. So
  the drawer rule (priority 200) is a catch-basin for any main-window frame no higher rule claims, and the frame gate
  then dedupes everything after it by identity. Of the 82 `side_nav_drawer` captures in the pull, only 23 show an
  open drawer (its container inset to top 120 / right 641 and the scrim container clickable); 54 are closed-drawer
  frames of something else (one is the offer card mid-dismissal, carrying `Decline`). Hypotheses: (a) the same
  mechanism hides the shopping list opened from a Compose offer card, with `offer_popup` as the claiming rule (the
  card's anchors stay in the tree under the list sheet, so the identity never changes); a capture is needed to
  confirm. (b) An open-drawer discriminator exists in the tree (the scrim's click action, the inset bounds), but it
  also appears in frames higher rules win (idle_map 9, ratings 7, offer_popup 4), so tightening the drawer rule is a
  design choice, not a one-liner. (c) The deeper shape is structural: identity dedup on a recognized target hides
  every novel surface rendered in the same window as a matched rule's anchors, and the census only ever sees UNKNOWN.
- 7 `PICKUP_NAV_STARTED` rows since 09-20 carry `storeName: "Unknown"` one frame before the real name (10-16 13:20:26,
  10-18 07:57:45 in this pull). Row-inert; noted for the event log's readability.

### Census
- The server's `silent_rule_death` alarm for `delivery_summary_collapsed` and `dropoff_photo` on UTC day 10-17 was a UTC
  day-boundary artifact: the 10-17 7:40 PM Central receipt landed on UTC 10-18, leaving UTC 10-17 without those rules.
  Checked on the server before this pull; no device-side action.
- Device side: `census uploaded=` lines on every dash day; 0 rejected, 0 `not_trusted`; the daily budget deferred uploads
  from 10-16 16:18 (`cause=budget`, #1193 class). `notificationRefused{POLICY_UNSUPPORTED_SCHEMA=8}`: the server policy
  does not yet advertise the notification schema.

### Verification TODOs / checklist
- Money: Σ delivered = reported to the cent on 7 of 8 summary dashes; the 8th is bug 1 ($37.07 unmatched). Σ reported
  $354.79, Σ delivered $317.72. No session over-attributes (#701). Per-drop miles never exceed the span (#688B, #1108);
  realized minutes 16.7 to 97.3, none negative.
- Moved to 1/2 (desk): #919 chat box masked (25 recognized, 2 UNKNOWN clicks, backstop line 10-17 17:16:12); #935 (11
  collapsed receipts, none with the drop-off card); #998 (return pay folded, shape caveat in bug 3); #1141 restaurant dwell
  1.69 and 3.23 min on the post-#1306 build; #1069 (eight same-store offers, eight rows); #916 (bubble lifecycle in
  `shareable.log`).
- Unchanged with notes: #1149 (7/7 expands clean; bug 4 breaks criterion 2), #1058 (leak A claimed 10 times but 3
  scrolled frames UNKNOWN, no name or address on them), #1311 (a real re-dash committed through `MODE_RESUME_COMMIT`
  10-17 18:22:32; no ghost seen), #1310 (269 of 281 rows carry `odometerAtArrival`; Settings half needs dev eyes), #1104,
  #1121.
- Unexercised: #1312, #1119 slice 1, #1304, #1314, #1315.
- WARN/ERROR census (post-install): 0 recognition ERROR; 1 `ERROR/App: Failed to get last known location` (gas price
  worker, 10-14 18:04); `ParseHealth` once per process on `dash_along_the_way`, `waiting_for_offer`, `idle_map`,
  `pickup_shopping`, all the documented benign baseline (01-sensor-pipelines); no `bindShortfall`, `bindRefused` or
  `bindUnprovable`; `mappingFailures=0`, `restarts=0`; 7 odometer `POOR_ACCURACY` rejections, each recovered.
- UNKNOWN census: 228 window, 159 click, 8 notification (DoorDash); 30 window, 11 click, 3 notification (Uber, 10-15 home
  screens). Largest families: scrolled drop-off sheets without a header (27, #1122 class, masked), partial offer cards (15,
  by design), the return flow (10, #998), offer card plus drawer with Accept (6; 2 are bug 1, 4 lack the disclaimer or a
  merchant row), the alcohol arrival card (3), Platinum rewards pages, the CSAT and safety surveys, help pages.
- PII sweep of the new UNKNOWN captures: no customer name, address, PIN or note in plaintext. Every street address found is
  a merchant address on an offer card or the pause-store sheet. Dasher-banking sweep: 0 hits in `captures/`; the
  `Transfer out`, `Transfer in`, `DasherDirect`, `Crimson` and `Driver's License` markers fired at the capture scrubber
  instead. `shareable.log`: no store name in any INFO+ line, no `[scrubbed:` placeholder.
