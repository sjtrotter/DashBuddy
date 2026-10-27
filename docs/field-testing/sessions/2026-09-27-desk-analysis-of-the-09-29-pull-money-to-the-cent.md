## 2026-09-27 → 2026-09-28 (desk analysis of the 09-29 pull — money to the cent; #1118's third clean run; four more accepts died; a stacked-job picker leaked two names; a dash stayed "online" 31 minutes after End dash)

**Date:** 2026-09-27 → 2026-09-28 (two dashing days) · **Platform(s) tested:** DoorDash (app **8.99.20**
on all 702 envelopes) · **Branch under test:** `master` at `1b596f74` (build `0.230.0+1b596f74`) — the
**second field run of PR #1125** and the third of PR #1118; nothing newer is on the phone. · **Field
conditions:** eleven sessions — 776 (09-27 11:35–13:45, 3 drops, $51.59, **the only Dash Along the Way
session**), 785 (13:45–14:37, 1, $23.80), 789 (a 7-second `early_offline`), 790 (15:58–17:20, 1, $36.35;
a support-chat removal at the store), 795 (09-28 08:28–10:25, 2, $50.67), 802 (10:57–11:30,
`early_offline`, 0 drops — Bug 3), **803 (11:31–11:37, `early_offline`: 14 offers, 13 declined, 1 timed
out in six minutes — a genuine decline run, see Field UX context)**, 804 (11:55–13:29, 2, $38.48), 811 (a
23-second `early_offline`), 812 (14:14–15:04, 1, $17.00), 816 (16:28–17:42, 1, $23.70). **59 offer
cards: 10 real accepts (DoorDash's own counters), 42 sheet-confirmed declines, the rest timeouts and
replacements.** Two pauses (both hand-resumed), no force-stop, no in-app unassign, no return order, one
stacked job (…-777, two stores, both arrivals seen). **One process for the whole window**, no recovery,
`restarts=0`, `mappingFailures=0`, `notifListenerDisconnects=0`, **zero ERROR lines**, no
`RecognitionHealth` / `LocaleBoundary` / `TtsHealth` notice. 702 capture envelopes; log coverage
continuous 09-27 11:07 → 09-28 18:52 (6 rotations + `app.log`). Pull dir `~/dashbuddy/logs/2026/09/29`,
device purged after verification; one raw-PII envelope purged from the pull (pseudonymized copy at
`~/dashbuddy/fixtures-pending/2026-09-29/`), three #1131 banking DEBUG lines purged. Desk report:
`~/dashbuddy/logs/2026/09/29/DESK-ANALYSIS.md`. **Money:** $241.59 reported = $241.59 recorded on all
seven summary sessions, to the cent; 8 `DELIVERY_RECEIPT_REPRICE` events, all no-ops.

### Bugs

1. **A stacked job's "Select an Order" picker shipped two customers' names raw (#1139, new Pledge
   leak).** In the pickup help flow on the two-store job, DoorDash 8.99.20's picker renders one row per
   order with `restaurant_name` + `instructions`, and `instructions` reads `For <First> <I> by HH:MM`
   (09-27 12:01:03). No rule claims it, `instructions` is not an `ID_MARKERS` suffix, and `For ` is a
   chrome-ambiguous prefix the backstop cannot carry — the #809 fused-`For ` class again, one screen
   before #1128's sheet in the same flow. The names reached no log. Raw file purged.

2. **#1119 — four of ten real accepts died; all four rescued by receipts.** `9d92b046` left on a PURE idle
   `waiting_for_offer` render (not the hybrid; `waiting_for_offer/2026-09-27_13-46-58-886…8bf4fe`),
   `e76c2783` on the HYBRID frame (`…2026-09-28_09-17-55-845…672d9b`), and for `6365ddeb` / `4220edb0`
   the leave frame was **deduped and never captured** — the transient frame's identity repeats across
   offers, so the suppressor hides the very frame that kills the accept. Running tally since #1118
   shipped: 14 of 35 accepts lost (40 %). Comment on #1119.

3. **A dash stayed "online" for 31.7 minutes after End dash (#1140, new).** Session 802: the
   `end_dash_confirm` screen was admitted at 10:59:12.5, the dasher tapped End dash and left DoorDash,
   and **no screen was admitted until DoorDash was reopened at 11:30:54**; `DASH_STOP` committed at
   11:31:04 as `early_offline`. Nothing was armed — an End-dash tap arms no grace, so this is not the
   #1054 strict-`>` timer bug but the same symptom by a different mechanism. The week's online hours
   and net-per-hour carry those 31.7 minutes. *Hypotheses only* (on the issue): the End-dash click
   envelope could arm a short pending end; the disappearance of the ongoing-dash notification (last
   seen 10:59:03, ~1 Hz while dashing) is a screen-free signal the notification pipeline ignores; or
   `endedAt` could back-date to the last admitted frame when the gap is large.

4. **A restaurant pickup's 16-minute wait folded to 0.0 minutes (#1141, new).** Session 804 ~11:50: the
   `arrived_at_store` tap was classified, but the wait survey / timeline / help screens that followed
   are UNKNOWN on 8.99.20 and carry no arrival flow, so `PICKUP_ARRIVED` never fired and the dwell has
   no anchor. Money reconciled; the store dwell and the Time tab's while-working split under-count.

5. **#301 through support chat a third time — and this time the next accept reused the abandoned task.**
   Session 790 (09-27 16:10): `pickup_resolution_options` → Contact support → chat → CSAT, 0
   `TASK_UNASSIGNED`. The next accept (a different H-E-B) reused task …-793 (the #810 orphan shape):
   `PICKUP_ARRIVED` re-emitted with the first store's 16:09:34 arrival, the real 16:25:10 arrival lost,
   dwell 55.5 min vs ~39.9 real; offer `d3c03872` left `ACCEPTED` in job …-791 → `JOB_ACCEPT_MISMATCH`
   inconclusive. DoorDash's own "You've been waiting a while — would you like to unassign?" screen
   (UNKNOWN 09-27 11:59:34, `order_self_unassign_*` ids) is a second deliberate unassign entry point.
   Comment on #301.

6. **#1131 recurred: three more Crimson-transfer DEBUG lines, one mid-dash.** Captures and
   `shareable.log` clean. Purged by hand (11 lines across four pulls now).

7. **Three receipt-less completions, all in the Dash Along the Way session (776), all photo →
   `dash_along_the_way`, all rescued by `OFFER_PAY`.** Every one of the window's 8 receipts came in
   normal mode. *Sharper hypothesis, one dasher's data:* Dash Along the Way skips the per-delivery
   receipt, which would make the offer link the only pay source in that mode. Comment on #1119.

8. **Two lost declines of the known flow-less-dwell kind:** 20.46 s on `on_dash_map`, and 15.12 s that
   ended in a card replacement (window 15 s). No new mechanism; the #1104 characterization holds.

### Field UX context

- **Session 803 was a decline run, not a defect:** 14 distinct cards (12 distinct pay/merchant pairs)
  each resolved through DoorDash's own confirm sheet in 1.5–10.6 s, only two started from the HUD, zero
  automation taps, then End dash. The ledger recorded every one.
- **The confirm-decline capability is still un-granted** (46 denials across the two days);
  `expand_earnings` is granted and fired on all 8 receipts, the two misses each recovering ~0.5 s later.
- Two "Replaced by new offer" timeouts at +19.5 / +19.7 s are real card turnovers; their successor
  offers deliberately get no `OFFER_RECEIVED` row (`OfferEffects.kt:63–65`) — not a defect.
- **"Open digital Red Card"** (click 09-27 12:23:08) produced no admitted frame for 24 s — nothing reached
  disk, which is the intended posture for that surface.

### Verification (desk)

- **#1118 / #1104 — third clean run (already retired at 2/2):** 42/42 declines inferred from the confirm
  sheet (median 2.19 s), 7/7 `OFFER_ACCEPTED` with a job (6 true + the support-removed order), no
  over-inference, **0 #1069 merges** (59 cards → 59 of every downstream record).
- **#1122 + #1123 (second run of PR #1125) — CLEAN, not a confirmation:** 0 UNKNOWN host-fragment hits
  (not even skeletons); on all 11 `dropoff_pre_arrival` + 4 `dropoff_handoff` envelopes the
  Settings→Safety slot is a Button plus two text-free Views (no name rendered); 0 workflow-sheet frames;
  every `Deliver to` hex equals that drop's `customerHash` prefix (8 customers). Still 0/2.
- **#1102 (retired) — re-observed clean:** 8 receipts → 8 expanded; two `Could not find any live node`
  each followed ~0.5 s later by a successful tap (the throttle restore's third and fourth sightings).
- **#1120 / #1033 L2 — not exercised:** 8 re-price no-ops; the one stacked job re-priced nothing.
- **#1108 — clean, not a confirmation:** `Σ realizedMiles ≤ span` everywhere; the stacked job has both
  arrivals and legs 8.91 + 2.13 mi; 0 negative `realizedMinutes`.
- **#1103 — clean negative (5th):** the four zero-drop sessions report NULL; back-to-back starts carry
  only their own totals.
- **Decline target bound — bind half holds a 4th time** (21 HUD declines → 21 verified candidates, 0
  unbound); one `bindShortfall{offer_popup_confirm_decline#confirmDeclineButton=1}` on a sheet render 1 s
  before that card's `OFFER_EXPIRY` (benign).
- **#1063 — desk half clean a 5th time.**
- **Unexercised:** #1121 (the `+$1`/`+$2` hits are the "Very busy" boost badge and peak-pay chips, not
  add-on deltas), #1107, #998, #1054 ×2 (two pauses hand-resumed; the End-dash gap is #1140), #1059
  (over-match passes an 8th time; `dasher_direct` dropped 388 frames incl. a mid-dash visit), #1058,
  #1034.
- **Censuses.** WARN by tag: `Effects` (46 confirm-decline denials, `GRACE_COMMIT` by design, the four
  expand-tap lines), `Pipeline` (customer backstop hits on the Bug 1 frame + the `Cr7` scrubs),
  `ParseHealth` (one benign bind shortfall above, the rest baseline), `Odometer` 0 rejections; ERROR 0.
  PII sweep over all 702 envelopes: the dasher's own `Stephen T`, merchant addresses raw by design,
  every `customer_message` push masked, `earnings_deposit` masked; banking sweep over `captures/` 0.
  UNKNOWN families worth rules (node shapes in the report §7): the Select-an-Order picker (Bug 1),
  DoorDash's long-wait self-unassign screen (Bug 5), the support-chat exit + CSAT sheets, the `This
  dash so far` sheet, the associate-verification sheet, "Not getting offers?", and the "We haven't
  seen movement" push.

### Open questions

- **#1140:** which signal should end a dash when the dasher leaves DoorDash right after End dash — the
  click, the vanished ongoing notification, or a back-dated `endedAt`?
- **Does Dash Along the Way skip receipts?** Three pulls of evidence now point one way; a normal-mode
  receipt-less drop would refute it.
- **#1119's deduped leave frames:** the fix's fixtures must include the case where the transient frame
  never reaches the classifier a second time.
- **The `instructions` id (#1139):** safe to add to `ID_MARKERS`, or does it carry DoorDash vocabulary on
  other surfaces? Check the corpus before widening the backstop.

### Meta

- Third consecutive pull with the confirm-decline capability denied on every sheet. The reinstall on
  09-26 reset consent; the automation cannot be measured until it is re-Allowed.
- The Dash Along the Way session is the first with three receipt-less drops in a row; worth a
  deliberate normal-mode comparison on the next dash.
