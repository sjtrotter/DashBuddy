## 2026-10-11 — two pulls (morning: 10-09/10-10 dashes; evening: five 10-11 dashes)

**Date:** 2026-10-11 · **Platform(s) tested:** DoorDash 8.101.8 · **Branch under test:** morning pull = `master` at `c5c32fc5` (post-#1302); evening pull = `master` at `db6d0a07` (post-#1307; carries #1303/#1305/#1307) · **Field conditions:** 5 dashes 11:25–21:38 (166/63/68/84/128 min), 11 deliveries, 1 unassign, gift order, two same-store H-E-B shops. Pull dirs `~/dashbuddy/logs/2026/10/11` and `…/11-2`; desk reports `~/dashbuddy/design/2026-10-11-pull/desk-report.md` and `…-pull2/desk-report.md`.

### Bugs
1. **P0 Pledge — gift order drop-off sheet UNKNOWN, `gift_recipient_name` raw ×4** (11:55–12:06). Not in `ID_MARKER_TABLE`; bare `First I` with no lead-in. → #1314. Originals purged (pull + device); redacted copies kept for rule authoring. Trusted-envelope copies on the census server: check pending (#1314 step 4).
2. **P1 — one job absorbed 4 offers / 5 drops for 2.5 h; session 1 recorded $16.00 of $85.99.** The gift drop never ARRIVED (its arrival surface was the UNKNOWN sheet in #1), so the job could never prove complete and every later accept joined it. Same-store re-accept (13:39) resolved onto the already-CONFIRMED H-E-B pickup task: `PICKUP_ARRIVED` re-emitted with the stale 12:44 arrival, HUD silent, real arrival eventless, confirm suppressed. → #1315 (new), #1120 (2nd sighting: "This offer $10.40" split $2.08 × 5), #1108 (reproduced: −124.6 min). Likely cause: the arrival gate on completeness is fail-null by design (#615); the fold-into-open-job path and the receipt-coverage path each need their own bound.
3. **P0 (known #1309) — help "Which order has an issue?" picker, `For <NAME> by HH:MM` raw** (17:49). 2nd sighting. Slice 1 (recognized pickup rules) is PR #1317; the sheets are slice 2.
4. **P3 — `offersAccepted` counts an unassigned accept** (session 3: 2 vs DoorDash 1). → #1316.
5. **P3 — `$0.00` dash-controls read after a $24.20 receipt** (19:23:55). Settle rule (g) should refuse; committed value unverifiable from the pull. → comment on #1041.

### Field UX context
- The dev's read was "an unrecognized navigation to a store". Recognition saw every frame of that leg (`pickup_pre_arrival` ×3, `pickup_navigation` ×2, the `arrived_at_store` click); the state machine hid it by folding the order into the confirmed task (#1315). Worth remembering: a silent HUD on a leg is not necessarily a recognition gap.
- 19:30:49: the dev tapped our heads-up Decline on a $40.70 offer, backed out of DoorDash's confirm sheet, then accepted it. Outcome recorded correctly (`OFFER_ACCEPTED`; #1104 precedence held). Open question for the dev: a #1254 near-miss (swapped buttons) or a change of mind?

### Verification TODOs / checklist
- #1141 restaurant dwell: 2 pickups, arrival on the first at-store frame, dwell 0.75 and 7.43 min — via `pickup_arrival` (70) / `pickup_post_arrival_multi` (99), not #1306 branch 1 (which claimed one mid-wait frame, no lifecycle role). Proposed: #1141 outcome 1/2; #1306 unchanged (branch 1 not the anchor today).
- #1149 receipt auto-expand: 6/6 clean (`Single verified candidate`, 1.0–1.3 s). Proposed 2/2 — dev's call.
- #1303 drawer give-up: clean (8 offer+drawer combos stayed UNKNOWN; 12 declines resolved from their own frames). Proposed 1/2.
- #1304 / #1121 / `dropoff_reminder` / #1311 / #1312: unexercised (all 23 offers TOTAL; no ghost session; the one unassign was single-order).
- #1116 (order-detail sheet all `[redacted]`), #919 (`message_input` masked), #1058 (alcohol arrival card ×3 masked), #1079 (`Apt/Suite` ×11 masked): 1 sighting each.
- UNKNOWN census 171 → 14 families; new: gift sheet (#1314), shopping sheets, help CSAT, self-unassign prompt, photo guidelines, 8.101.8 earnings page (→ comment on #1313; `fixture-candidates.txt`).
- WARN/ERROR: 0 ERROR, no recovery, no TTS loss, `mappingFailures=0`; census daily budget reached 16:54 (#1193).

### Meta
- Pull-2 desk analysis ran as an opus agent (~16 min); the store-navigation hypothesis was answered from the observation journal + `app_events`, not from captures alone.
