## 2026-10-26 (desk analysis of the 10-26 pull: dashes 10-18 evening to 10-25; two accepts recorded as timeouts cost $21.40; two Pledge findings in recognized data)

**Date:** 2026-10-26 (pull) · **Platform(s) tested:** DoorDash 8.103.4, then 8.104.3 from 10-22 20:06; Uber 4.601/4.602 on 10-21 only (7 offers, all declined) · **Branch under test:** `master` at `d807d3f3` (`app=0.230.0+d807d3f3`), one process from 10-20 07:46 to the pull (`restarts=0`, `mappingFailures=0`); 18 dev-build installs on 10-18/19 before it, none during a dash with deliveries. The phone does not have #1383, #1385, #1387, #1388 or #1361 · **Field conditions:** 12 sessions (11 DoorDash, 1 Uber), 5 ended on the dash summary; 14 delivery rows; 47 DoorDash offers recorded as 13 accepted, 30 declined and 4 timed out (two of the timeouts were accepts and one a decline, bugs 1, 2 and 5). Two short 10-20 evening sessions were HUD and heads-up testing (16 offers declined from the heads-up notification). Pull dir `~/dashbuddy/logs/2026/10/26` (1,006 captures, 18 rotations, DB); desk analysis in the same folder.

### Bugs
1. **P1: an add-on accepted in the middle of a job was recorded as a timeout** (10-23 17:48:44, offer `f09cecc0`, "+$8.00 / +2 stops"). The card was recognized and spoken as an add-on, then left through an UNKNOWN frame and `pickup_pre_arrival` 13 s into its 47 s countdown; `OFFER_TIMEOUT` fired and the HUD said "Offer Timed Out!". The add-on's Little Caesars pickup and drop then ran inside the job. Likely cause: the documented fail-null branch (a click-less accept is inferred only over a non-task return flow). Session 1088 is $10.00 short: this $8.00 plus bug 6. The job's $9.00 quote was split across both drops.
   - **Status:** Open (#1119, evidence comment).
2. **P1: an accept in the last second lost to the expiry timer by 38 ms** (10-25 13:12:44, offer `4a054732`, $13.40 H-E-B). `OFFER_EXPIRY` fired at the countdown's end and was processed before the first pickup frame, whose snapshot was taken earlier. The job minted with no offer and no receipt was shown, so the drop folded `payBasis NONE` (net −$2.29). Session 1105 is short exactly $13.40.
   - **Status:** Open (#1119, evidence comment).
3. **P1 Pledge: a mid-render pickup frame stored the customer's name as the store name** (10-20 14:50:51.700). The customer card rendered above the merchant row, and `pickup_pre_arrival` reads `storeName` from the first `user_name` node. The raw name is in `app_events` sequence 4514 and one `chat_messages` row on the phone and in the pull; the capture itself is masked. Whether to scrub the stored row is the developer's call.
   - **Status:** Open (#1396).
4. **P1 Pledge: a merchant pickup instruction carried the customer's name and a locker code** (10-23 17:54 and 17:55, Little Caesars, recognized `pickup_arrival`). The `instructions_line_2_*` nodes are neither parsed nor redacted. The text is only in the two debug captures, which need purging once a scrubbed fixture exists.
   - **Status:** Open (#1397).
5. **P2: a decline confirmed 15.4 s after the confirm sheet appeared was recorded as a timeout** (10-20 16:08, offer `74ddaa75`). The sheet window was snapshotted 72 times with no frame admitted, so `declineSheetSeenAt` kept its first sighting. Open question for the developer: was "Decline offer" pressed at about 16:08:46?
   - **Status:** Open (#1398).
6. **P2: a "Tip Update" push ran its handler twice and its $2.00 was stored nowhere** (10-23 17:49:49). Two INFO lines, two Dispatch lines and two bubble posts in one millisecond.
   - **Status:** Open (#1400).
7. **P2: an order removed through support at the store left a phantom pickup confirmed at the dash end** (10-21 15:43 to 15:45, H-E-B). No `TASK_UNASSIGNED`; `endSession` stamped `PICKUP_CONFIRMED` at 15:45:57.400, leaving a 3.8 min dwell row and an unlinked accept.
   - **Status:** Open (#301, evidence comment).
8. **P3: Uber's Driver's License screens were dropped by the marker backstop, not a sensitive rule** (10-21 15:12, offline), and three DoorDash "Transfer out" taps reached the backstop as UNKNOWN clicks while the screen under them was blocked. Nothing reached disk.
   - **Status:** Open (#1402).
9. **P3: DoorDash 8.104.3's offer push is UNKNOWN on every offer.** It now arrives as "New Delivery!" with no body on `…nexus-new-offer-sound-v3`; `new_order` went from 13 captures on 8.103.4 to 0. Offer recognition is unaffected.
   - **Status:** Open (#1399).
10. **P3: "Mark as delivered" on the handoff screen is an UNKNOWN click** (10-25 13:12 and 16:19). The `complete_delivery` click rules do not name `dropoff_handoff`. Both drops had arrival evidence. (The desk analysis placed the tap on `dropoff_completed_confirm`; the click envelopes carry `screenTarget: dropoff_handoff`, and the confirm sheet was admitted about half a second later.)
    - **Status:** Open (#1401).

### Field UX context
- DoorDash 8.104.3 (10-23 and 10-25): every lifecycle rule matched, and the offer count equals DoorDash's `offersTotal` on every dash. New UNKNOWN families are transitional or peripheral: offer cards mid-exit (9), the drop-off screen before its sheet fills (4), the pickup sheet mid-render (3, masked), `prism_sheet` bottom sheets incl. "Can't verify items" (7), the dasher's ratings tier pages (7), the "This dash so far" sheet (3).
- 10-23: no receipt rendered on any drop; every between-delivery wait was the along-the-way card, so all five rows are `OFFER_PAY`. Two drops completed through "Complete delivery steps" with no `dropoff_photo` frame, so no `DELIVERY_ARRIVED` fired; the `complete_delivery` tap (the #1315 witness) closed both jobs and their mileage is `LEGACY_SPAN`.
- 10-25 12:47: the shopping aisle-locator search box is an editable field; the `input=editable` backstop masked it 4 times.

### Census
- The server's `silent_rule_death` alarm for UTC day 10-23 is a false positive. Each rule it named had a plain reason to be silent that day: every between-delivery wait was the along-the-way card, there were no declines, and no receipt rendered collapsed. On 10-25 all three rules matched.
- Device side: `uploaded` lines on every dash day, 58 accepted batches, 0 rejected, no deferrals logged, `envelopesPaired=166 / Unpaired=0`; `notificationRefused{POLICY_UNSUPPORTED_SCHEMA=9}` (the server policy does not yet advertise the notification schema).

### Verification TODOs / checklist
- Money: 10-20, 10-21 evening and 10-25 15:25 reconcile to the cent. 10-23 is $10.00 short (bugs 1 and 6); 10-25 12:21 is $13.40 short (bug 2). No session over-attributes (#701); `drop_miles ≤ span` on every session (#688B).
- WARN/ERROR census: 0 ERROR. `ParseHealth` once per process on the documented benign baseline; no `bindShortfall`, `bindUnprovable` or `bindRefused`. 9 customer-PII backstop hits on UNKNOWN frames (5 `user_name` on mid-render pickup sheets, 4 the aisle-locator box). No crash and no recovery on `d807d3f3`.
- PII sweep: dasher-banking greps 0 hits; no customer name, address, phone or door code in any new UNKNOWN capture; 13/13 `message_input` nodes read `[redacted]`. Findings in recognized data are bugs 3 and 4.
- Heads-up and automation taps: 26 Decline taps and 1 Accept from the notification, 4 `expand_earnings`; all 31 logged `Single verified candidate`, none stale, inconclusive or twinned.
- Feature data on `d807d3f3`: 14 `ODOMETER_READING` events (5 within 20 s of a dash start), car versus GPS within about 1 mi per in-dash span; 11 `DASH_VEHICLE` START stamps for 11 sessions; one Gas fill-up attributed to the live dash. Cash tip, expense keypad, goal-met and undo left no durable trace (dev eyes only).
- Checklist moves: #1149, #935, #749, #1069 and #1141 reach 2/2 and move to §5; #1121 marked broken in part (bug 1); notes added to #919 (pending dev confirmation that chat text was typed), #1167, #1315, #1312, #1311, #1058, #1002, #1360 and the S2f, S3b and S2g items.
