## 2026-09-16 → 2026-09-19 (desk analysis of the 09-20 pull — DoorDash's new Compose offer card blanked two whole days of offers; #1078 caught three more dash-end drops; a timeline sheet's SECOND frame shipped an address raw)

**Date:** 2026-09-16 → 2026-09-19 · **Platform(s) tested:** DoorDash (app **8.97.8**, every envelope —
unchanged across the 09-18 offer-card rollout, so that was a server-side UI flip, not an app update) ·
**Branch under test:** `master` at `1bb09795` (the PR #1112 merge; read off the logs — build
`0.230.0+1bb09795` on the startup line ×4 processes and on the `app=` head of all 112 `PipelineStats`
lines, per PR #1066). **First field run of #1110 / #1111 / #1112.** · **Field conditions:** fifteen
DoorDash dashes over four days — 09-16: 578 ($64.55, 2 drops), 585 ($20.25), 589 ($14.50); 09-17: 593
($47.29, 3), 601 ($76.30, 3); 09-18: 611 ($48.30, 2), 618 ($23.71); 09-19: 622 ($18.51, 2), 630
($156.34, 5), 646 (`early_offline`), 647 ($61.55, 2), 654 + 655 (`early_offline`), 656 ($21.03), 660
(`early_offline`). **23 drops, $552.33 reported, 241.75 mi of odometer span.** 23 offers were
recognized on 09-16/17 (4 acc + 7 dec + 1 timeout, then 5 acc + 6 dec + 0 timeout) and **zero** on
09-18/19 — the Compose rollout, Bug 1. Uber Offline throughout; no pause, no force-stop, no unassign,
no return order, one stacked job; two app starts (09-15 23:51, 09-17 23:56) and **zero crash
recoveries**. 1 440 captures (1 105 window / 314 click / 21 notification; 159 UNKNOWN windows, 166
UNKNOWN clicks, 21 UNKNOWN notifications), **112 WARN / 0 ERROR**, `restarts=0` and
`notifListenerDisconnects=0` across the window, no `RecognitionHealth` / `LocaleBoundary` /
`TtsHealth` notice, `shareable.log` PII-free. One capability granted (the #1093 receipt-expand);
`confirm_decline` still denied fail-closed 23×. Log coverage continuous 09-15 20:35:56 → 09-20
07:30:13 across 13 rotations + `app.log`, no gaps. Pull dir `~/dashbuddy/logs/2026/09/20`, device
purged after verification. Desk report: `~/dashbuddy/logs/2026/09/20/DESK-ANALYSIS.md`; Astra's
independent preliminary review of the same pull: `~/codex/dashbuddy/2026-09-20-preliminary/REVIEW.md`.

### Bugs

1. **DoorDash moved the offer card to Compose on 09-18 and DashBuddy recorded ZERO offers for two
   dashing days.** 13 offer-shaped frames fell to `UNKNOWN/` and another 23 were misfiled as
   `side_nav_drawer/` (that rule's `side_nav_content_container` + ratings/promos/preferences anchors
   coexist with the offer content in the captured tree); `app_events` holds **no `OFFER_*` row of any
   type and no `offer_records` row on either day**, against 12/12 on 09-16 and 11/11 on 09-17. All 36
   frames lack `accept_button`, `accept_decline_footer_container` and `display_name`, which the
   `offer_popup` rule requires. First frame: `UNKNOWN/2026-09-18_14-46-44-021…4ff4dc.json` (Dollar
   Tree, $17.75, 7.6 mi); a clean live one: `UNKNOWN/2026-09-19_11-42-11-094…b7abab.json` (H-E-B,
   $14.75, 8.5 mi, Accept + countdown); last old-layout recognized card
   `offer_popup/2026-09-17_13-44-02-213…c7aded.json`. **Money consequence:** all six `payBasis = NONE`
   rows of the pull (Verification 1) are on these two days — with no `OfferEvaluation` to fall back on,
   a drop is priced only if a receipt is read. Everything else in the lifecycle held without offers:
   jobs were still minted from the pickup surface (click-less accept) and each ran the full
   `PICKUP_NAV_STARTED → … → DELIVERY_COMPLETED` chain, 23/23 rows carry `storeName` + `storeKey`, zero
   ghost offers, zero `JOB_ACCEPT_MISMATCH`. The **push** moved the same day too: the offer
   notification's `channelId` flipped from `dasher-notification-channel-new-order-v2` (11 envelopes,
   09-16/17) to `dasher-notification-channel-nexus-new-offer-no-sound-haptics` (16 envelopes,
   09-18/19), so every offer push fell UNKNOWN and `NOTIFICATION_RECEIVED: NEW_ORDER` stopped firing —
   the `nexus` token is the same rollout. *Hypothesis:* a server-side UI flip inside 8.97.8 (the
   evidence brackets it between 09-17 13:44 and 09-18 14:46 but does not establish the rollout
   mechanism).
   - **Status:** Shipped in #1115 (2026-09-20, merged at `aab8d960`) — a second `offer_popup` branch
     for the Compose card, the drawer rule taught to reject it, and both channel ids accepted in
     `notifications.json5`. **Unvalidated in the field:** the checklist already carries #1115's own
     watch item (added by that PR), including whether an ACCEPT still mints the job and whether a
     DECLINE lands as a decline or as a timeout.

2. **PLEDGE — the timeline order-detail sheet escapes #985 on its SECOND frame and shipped a customer
   address and instruction note raw to disk.** The sheet renders in two beats and only the first is
   claimed:

   ```
   16:08:33.349  UNKNOWN              → desc 'Close sheet', text '[redacted]'             (name only)
   16:08:33.509  timeline_task_detail → 'Deliver to [redacted:d03b]', 'Copy address'      ← rule fires, masked
   16:08:33.734  UNKNOWN              → a raw street line, a raw City/ST/ZIP line,
                                         a raw customer note (unit number + directions), 'Hand it to customer' ← RAW to disk
   ```

   Evidence file `UNKNOWN/2026-09-18_16-08-33-734__doordash__accessibility.window__UNKNOWN__f06a47.json`
   — raw street line, raw city/ST/ZIP, and a raw quoted instruction body carrying a unit number; the
   name alone was caught by the `user_name` ID-marker backstop. The #985 rule requires **both**
   `Copy address` (text) and `Close sheet` (contentDescription), and the fully-inflated frame 225 ms
   later no longer carries `Copy address`. *Hypothesis:* the same class as #1058 leak B and #803 — an
   id-less customer-instruction body on a sheet a structural anchor failed to claim; either #985's
   second anchor is too tight for the inflated frame, or the address/note shapes belong on the
   UNKNOWN-path backstop for this sheet. This file was the **only** PII hit in a sweep of all 1 440
   envelopes (Verification 10); it has been purged from the pull, and the device was already purged,
   so no copy remains.
   - **Status:** Open (#1116).

3. **The offer confirm-decline sheet has no button label at all on 8.97.8 — the decline anchor is
   fully rotted, and the #1104 `screenIs` widening cannot have fired.**
   `offer_popup_confirm_decline/2026-09-16_18-40-14-504…83204c.json` carries only copy lines (`Are you
   sure you want to decline this offer?`, `Does not lower acceptance rate`, `30%`, `A high acceptance
   rate is required…`, `Pause offers from this store`, `Thanks to your Gold status`) — **no `Decline
   offer` node**. Consequences, all observed: final `PipelineStats`
   `bindShortfall{doordash.screen.offer_popup_confirm_decline#confirmDeclineButton=25}` (it was 3 WARNs
   on the 09-15 pull), an earlier process line carrying `offer_popup#declineButton=2`, two
   `ParseHealth … optional bind 'confirmDeclineButton' resolved no node — target anchor rot?` WARNs,
   one `No 'declineButton' target bound for doordash — decline_offer unavailable`, and **zero** clicks
   classified `confirm_decline` in 1 440 envelopes. *Hypothesis:* this, not the click/window race, is
   the live remaining mechanism behind #1104's fielded shape — and it may not be repairable from the
   click side at all: **#1115's new Compose `confirmDeclineButton` bind restores the TARGET, but a
   human tap on a Compose control emits no `TYPE_VIEW_CLICKED` at all**, so no click envelope can
   arrive to be classified (research note
   `~/dashbuddy/research/compose-click-capture-2026-09-20.md` §0–§1: Compose emits that event only for
   an accessibility-performed `ACTION_CLICK`, which is why DashBuddy's own automation taps still
   produce envelopes while the dasher's do not; the note is linked from #1114's comment). If that
   holds, a decline would have to be inferred from the semantics the tap causes rather than from the
   tap itself — unconfirmed, and no issue is filed for it yet.
   - **Status:** Partially closed — the bind half shipped in #1115 (2026-09-20); the "can a human tap
     ever be observed" half is open and unfiled. The decline-bound checklist item stays live carrying
     this evidence.

4. **A new unrecognised handoff-photo instruction surface.**
   `UNKNOWN/2026-09-18_15-36-07-896…0bf650.json` — `description_item: 'Take a photo of the handoff
   location'`, `first_caption: 'Take a door photo\nInclude the apt/suite number if there is one'`,
   plus `second_caption`, `Continue`, `Contact support`, under `drop_off_workflow_host_fragment`. No
   PII on this render. *Hypothesis:* by the #1107 / #1058 argument this is exactly the kind of wrapper
   a customer note can inflate into, so it is a recognition gap today that could become a leak later.
   - **Status:** Open (logged, low — not filed).

### Verification (desk)

1. **Money: $442.94 of $552.33 attributed; the $109.39 gap is six `payBasis = NONE` rows and is
   mechanically Bug 1.** Per-session `Σ realizedPay` equals `reportedEarnings` **to the cent on nine of
   the ten sessions that priced**, and the five `early_offline` zero-delivery sessions all report NULL.
   Σ pay never exceeds reported anywhere (the #701 over-attribution check passes). Attribution splits
   `DROP_SHARE` 11 rows / $307.07 + `OFFER_PAY` 6 rows / $135.87; every one of the six unattributed
   rows is on 09-18/19 and each carries its own WARN pair, e.g. `09-18 15:37:39.797 DEBUG/StateMachine:
   #997 offer-pay attribution degraded to JOB_POOLED: job job-doordash-1789760804545-612, 0 offer(s)
   over 1 drop(s)` → `WARN/StateMachine: #691 offer-pay estimate eligible but unsplit … these dollars
   ride the unattributed bucket`. Of the 13 drops on the offer-less days, 7 landed a collapsed receipt
   and priced `DROP_SHARE`; 6 never saw a receipt frame at all (mid-route or dash-end completions) and
   fell through to `NONE`.

2. **#1078 + #1095 CONFIRMED a second time — RETIRED at 2/2 (moved here from the checklist).** Three
   drops were recorded at the `DASH_STOP` edge that the pre-#1100 build would have lost silently:

   | seq | when | `completedAt` stamped | `DASH_STOP` `occurredAt` | gap |
   |---|---|---|---|---|
   | 2730 | 09-18 | 17:12:43 | 17:12:54 | 11 s |
   | 2758 | 09-19 | 09:37:21 | 09:47:57 | **10 m 36 s** |
   | 2832 | 09-19 | 20:36:19 | 20:36:30 | 11 s |

   In each, the `DELIVERY_CONFIRMED` + `DELIVERY_COMPLETED` pair sequences immediately **after** the
   `DASH_STOP` row at the same `occurredAt`, with `completedAt` stamped at the absorbed retire's arm
   instant — the honored-retire signature. Seq 2758 is the strongest evidence: the drop confirmed at
   09:37:31, then the phone went idle for ten minutes so **no frame ever arrived to lapse the retire's
   10 s grace** (lazy expiry); the `SESSION_END` at 09:47:57 absorbed the still-live retire and
   `endSession` honoured it at its matured deadline. Pre-#1100 that dash records nothing. Across the
   pull: 23 delivered drops → 23 `DELIVERY_COMPLETED`, `Σ realizedPay == reportedEarnings` on every
   session that priced, **zero `JOB_ACCEPT_MISMATCH`** (so #1095's false-positive half passes a second
   time). The deliberate < 10 s End-Dash off the drop-off photo was never scripted, but the two 11 s
   gaps are that shape and the ten-minute idle exercises the absorption harder than the script would —
   the item is retired on that basis. One open question rides along (Open questions 1).

3. **#1102 CONFIRMED on its first field run (1/2).** `Performing expand_earnings` = 14,
   `Throttled action` = **0**, `Could not find any live node for: expand_earnings` = 2 — and both
   mid-slide failures were rescued by the retry *inside* what used to be the throttle window, which is
   precisely the fix:

   ```
   09-18 18:12:02.939 WARN/Effects: Could not find any live node for: expand_earnings … (id=null, text=null, bounds=…)
   09-18 18:12:03.465 INFO/Effects: Performing expand_earnings on doordash          ← +526 ms, NOT throttled
   09-19 12:19:53.486 WARN/Effects: Could not find any live node for: expand_earnings …
   09-19 12:19:54.293 INFO/Effects: Performing expand_earnings on doordash          ← +807 ms
   ```

   **12 distinct receipts presented, 12 expanded (100 %)**, against 6/11 on 09-13 and 16/18 on 09-15;
   collapsed → expanded latency 0.9–1.4 s on every one; 11 of the 12 produced a `DROP_SHARE` row (the
   twelfth is Open questions 2). Needs one more clean dash.

4. **#1103 UNEXERCISED, clean negative.** `settle gate: receipt running-total read refused` = **0
   hits** — the causing shape (a previous dash's receipt still up at `DASH_START`) did not recur, and
   there were no *false* refusals either. All five `early_offline` zero-delivery sessions report NULL
   `reportedEarnings`, including 646, which started 3 min 3 s after session 630 closed on $156.34. Six
   `SESSION_PAY_SETTLE` timers fired, each inside its own session and each committing that session's
   own figure; no duplicate `Delivery - <amt>.png` / `DeliveryBreakdown - <amt>.png` pair straddles a
   dash boundary; no `DELIVERY_RECEIPT_REPRICE` is attributed to a following session.

5. **#1104 IMPROVED, not closed — and the mechanism has moved.** One `OFFER_TIMEOUT` in 23 offers on
   09-16/17, against 9-of-12 misrecorded on the 09-15 pull. That one **was** a real decline (offer
   `0c4fc920`, 09-16 18:40): `Captured click: target=initial_decline` 18:40:12.880 → `UNKNOWN click —
   id=null text=null` 18:40:14.036 (the confirm tap) → `Captured screen:
   target=offer_popup_confirm_decline` 18:40:14.504 → `[Dispatch]: Offer Timed Out!` 18:40:15.104. But
   there are **zero clicks classified `confirm_decline` anywhere in the pull**, so PR #1110's
   `screenIs`-array widening cannot be shown to have fired at all — the anchor it would fire behind is
   dead (Bug 3).

6. **#1107 CONFIRMED on its recognition half; the redaction half is UNEXERCISED.** The 8.97.8 "Drop off
   steps" wrapper is live and claimed: **5 `dropoff_step_instructions` envelopes** (09-19), none in
   `UNKNOWN/` — first sighting of this folder. Every fielded frame carried only DoorDash's own step
   copy (`Leave it at the door`, `Verify correct order`, `Check you have the correct order from
   pick-up`, `Take photo of drop-off location`, `Handed order to customer`) with **no
   `description_text_view` node rendered on any of them**, so the `plainMask` on that node never had
   anything to mask. `step_title` / `step_description` correctly stay raw (app vocabulary, per the
   rule's own comment). The leak shape that produced #1107 needs an order that actually carries a
   customer instruction body.

7. **#987 CONFIRMED (1/2).** **11 of 11** `earnings_deposit` envelopes are fully masked, plain (no
   hex), all four fields: `{"title": "[redacted]", "text": "Your Dasher earnings for [redacted]",
   "tickerText": …, "bigText": …}`. The dasher's own figure and the #599 Crimson clause are both gone —
   the asymmetry logged as Bug 9 of the 09-15 entry (twelve unmasked instances across seven pulls) is
   closed on the recognized path. The playbook's dasher-banking sweep over the whole `captures/` tree
   returns **0** hits on all three grep families (`available balance|dasherdirect|crimson|routing
   number`, `transfer out|transfer in|cash out|instant pay`, `transfer +\$[0-9]`). First sighting since
   PR #1110 shipped; a checklist item now carries the second pass.

8. **#1058's workflow sheet EXERCISED for the first time, and clean.** `grep -c dropoff_workflow_sheet`
   = 2 (non-zero for the first time in five pulls), and the single `dropoff_workflow_sheet` envelope
   (09-17 10:59) is graded correctly: `[redacted:de0f]` for the name, **plain** `[redacted]` ×5 for the
   address lines / unit / note, `Leave it at the door` kept, merchant item descriptions kept. The
   adjacent positive holds — all 34 `dropoff_pre_arrival` envelopes mask correctly. Check 1's grep
   returns 7 UNKNOWN hits: 6 are the documented text-free loading skeletons and the 7th is Bug 4. The
   **alcohol** arrival card (leak A) did not render this window, so the item stays open for that half.

9. **#995 CONFIRMED on one frame.** `pickup_receipt_scan/2026-09-19_09-11-35-106…`: `Focus on
   [redacted:d67c]` / `Scan customer name` / `[redacted:d67c]` / `#12f24 · DoorDash` / `No receipt` —
   recognize-and-redact working as designed on 8.97.8.

10. **PII sweep of all 1 440 envelopes: one file, and it is Bug 2.** Walked `text` / `desc` /
    `stateDescription` / `hintText` and the flat notification fields for two-capitalised-word names,
    `[A-Z][a-z]+\s{1,4}[A-Z]\.`, `Pickup for` / `Deliver to` / `Delivery for` / `For <Name>`, `pin|gate
    code|entry code \d{3,}`, street addresses, `Apt|Unit|Suite \d+`, `City, ST 12345` and `Customer
    Notes`. Everything else found is allowed by design: merchant addresses raw (#886 —
    `pickup_navigation` ×14, `side_nav_drawer` ×13 incl. the Compose cards, 4 UNKNOWN Compose frames),
    `Customer Notes` ×8 in `shopping_item/` as the **label** only (`body_text_view: [redacted]`, fused
    `desc: Customer Notes: [redacted]`), and `Leave it at the door` as an instruction *title* on a card
    whose `user_name` / `address_line_1` / `address_line_2` are all `[redacted]`. Zero PII in any click
    or notification envelope; no dasher-name hits anywhere. The one offending file is purged.

11. **#1033 Layer 2 stays 1/2 (fifth consecutive pull without the deliberate > 8 s expansion).** Fired
    11×, no-op'd 11×, `receiptRepricedAt` NULL on all 23 rows, zero `DELIVERY_RECEIPT_REPRICE: no
    delivery row` WARN, and `rows_total == receipt_total` to the cent on all 11 receipt-priced jobs.
    Layer 1 is comfortable — every collapsed→expanded transition landed in 0.9–1.4 s. One possible new
    gap is in Open questions 2.

12. **#1054 UNEXERCISED for a FIFTH consecutive pull, both items.** Zero `DASH_PAUSED` / `DASH_RESUMED`
    / `MODE_RESUME_COMMIT` / `SESSION_PAUSED_SAFETY` in the window, no force-stop mid-grace, and no
    `Recovery re-armed` (correctly absent — neither of the two process starts restored a live grace).
    Baseline healthy: eleven summary ends at `occurredAt − endedAt` = 2.502–2.506 s, four early-offline
    ends at 10.002–10.004 s, no end drifted. Only the two on-dash exercises (background an offline
    screen; force-stop mid-pause) can move these.

13. **#1059 UNEXERCISED for a fifth pull, with the over-match check passing a fifth time.**
    `grep -rlE 'persona_container|personaComposeView|activate_physical_card_button|id_type_selector'`
    over the whole `captures/` tree returns **0**, and no `sensitive.selfie_verification` /
    `sensitive.red_card` / `sensitive.id_verification` drop fired. 269 `pickup_shopping` + 125
    `shopping_item` frames (many Red-Card shop orders) produced **zero** `sensitive.red_card` drops.
    Pre-existing arms healthy: `sensitive.dasher_direct` ×500, `sensitive.balance` ×25,
    `sensitive.crimson_balance` ×11, `sensitive.catchall` ×34.

14. **WARN / ERROR census: zero `ERROR` lines in the whole four-day window, 112 WARNs, `restarts=0`.**
    The census head: 35 `Timer Expired: GRACE_COMMIT` (the designed commit path), 23 `Denied
    confirm_decline … (fail closed)` (Open questions 3), 15 `Capture backstop: UNKNOWN screen carried
    customer PII (nodeId=user_name) — scrubbing node`, 6 `#691 … unsplit` (Verification 1), 6 + 4 + 2 +
    1 `Capture scrubbed: UNKNOWN notification / click / screen hit sensitive marker`, 3 `Odometer fix
    rejected (POOR_ACCURACY) … entering a rejection streak`, 2 + 1 anchor-rot `ParseHealth` lines
    (Bug 3), 2 + 2 `expand_earnings` resolution lines (both rescued, Verification 3), and the
    known-benign `waiting_for_offer` / `pickup_shopping` / `dash_along_the_way` parse-shortfall
    baselines. The 18 `Capture backstop` / `Capture scrubbed` WARNs are the privacy layer **working**
    on UNKNOWN frames — the 15-hit `user_name` cluster is the offer-less days' task cards falling to
    UNKNOWN and being scrubbed on the way to disk. Final `PipelineStats`: `forwarded=1650
    dupSuppressed=50963 unknownDropped=272 sensitiveDropped=276 noiseDropped=0 mappingFailures=0
    awaitingRulesDropped=0 unknownScrubbed=9 redactBackstopScrubs=0 unknownCustomerScrubs=15
    notifListenerConnects=1 Disconnects=0 restarts=0` — the #430 restart family and the #731 listener
    flap are both quiet.

15. **UNKNOWN census.** **Windows 159** (09-16: 38 · 09-17: 20 · 09-18: 45 · 09-19: 56): 22 side-nav /
    drawer chrome with no content, 17 text-free chrome frames, **13 Compose offer cards (Bug 1)**, 13
    Google-Nav sheet / maneuver chrome, 13 task-card variants with an address block, 12 text-free
    workflow-host loading skeletons, 7 id-less `Close sheet` bottom sheets (the timeline family of
    Bug 2), 6 legacy offer-card half-renders (the #595/#1063 family the check permits), 4
    shopping-item sub-sheets, 52 one-off dialogs — plus the 23 Compose offer frames misfiled under
    `side_nav_drawer/`. **Clicks 166** — all ordinary unmapped in-app buttons, none carrying PII
    (`Confirm` ×9, `Continue` ×9, `End dash` ×8, `Scan again` ×7, `Got it` ×7 …); **no Compose
    Accept/Decline tap appears in any click envelope**, which corroborates Bug 3's mechanism, and the
    09-19 growth (73 clicks vs 33 on the offer-rich 09-17) is shopping-flow chrome, not offer taps.
    **Notifications 21**, of which **18 are the offer push** on the new channel (Bug 1).

16. **Also clean:** #1108 — no recurrence (`Σ realizedMiles ≤ (lastOdometer − startOdometer)` on all 15
    sessions, largest gap 0.03 mi; zero negative `realizedMinutes`; the one stacked job, …-594 with two
    drops, shows legs 21.98 vs span 21.98); #1063's desk half a **fifth** time for 09-16/17 (23 offers
    → 23 `OFFER_RECEIVED` → 23 TTS utterances → 23 `Offer -` screenshots → 23 chat cards → 23
    resolutions, zero `Replaced by new offer` / `Superseded by direct offer`, and
    `parseShortfall{doordash.screen.offer_popup…}` absent entirely); #733/#159/#1000 store resolution
    (23/23 rows carry `storeName` + `storeKey`, zero `D6 join miss`, all 9 accepted offers carry
    `linkedJobId` **and** `storeKey`); #991 no-regression (23/23 `speak()` succeeded, zero `-1`, zero
    re-init, zero notice); #862 scrub visibility (zero `[scrubbed:` residuals and no raw
    merchant/customer text in the INFO+ `shareable.log`); #315 H5 (23 pickup dwells, 0.01–56.22 min —
    the 0.01 min row is Open questions 4); #996/#997 partially (6 `DELIVERY_COMPLETED` carry
    `PER_OFFER_STORE`; the 6 degrades are all `JOB_POOLED` with `0 offer(s)`, the #1114 artefact rather
    than a store-reconcile failure — shapes (b) and (c) are still unseen).

17. **Not exercised:** #998 (no refused or returned delivery); #301 (zero `TASK_UNASSIGNED`); #1034
    (`grep -c '\$-' shareable.log` = 0, and no window went negative); #967 (no Offline ratings browse);
    every Uber item; every dev-eyes UI item.

### Open questions

1. **Seq 2758 has `arrivedAt = NULL` and there is no `DELIVERY_ARRIVED` event for task …-629** (09-19
   shows 9 arrivals against 10 completions — this is the one), yet `absorbableRetire` is documented to
   require an ARRIVED dropoff. *Hypothesis:* either the `Task` carried an arrival the event stream
   never emitted, or a different path (the `endSession` close-out sweep) stamped this row. The outcome
   was correct either way, but which path it was matters, because #1095's lifted single-accept floor is
   keyed on the ARRIVED shape.

2. **The 09-17 12:01 drop is the shape #1033 Layer 2 exists for, and Layer 2 did not fire.** Seq 2676,
   job …-598: the completion committed on a receipt-less PostTask exit at 12:01:10.782
   (`WARN/Effects: Timer Expired: GRACE_COMMIT`), the collapsed receipt did not appear until
   12:01:21.465 — **10.7 s later** — the auto-tap expanded it at 12:01:21.985, and the screenshot pair
   reads `Delivery - 23.25` / `DeliveryBreakdown - 23.25`. No `DELIVERY_RECEIPT_REPRICE` was emitted
   for that job and the row kept `payBasis = OFFER_PAY $23.25`. **No money impact** — the offer
   estimate happened to equal the receipt to the cent. The obvious refusals do not obviously apply:
   `lastAcceptResolvedAt` for this platform was 11:02:05, well *before* `receiptSeenAt`, and the
   12:01:14→12:01:21 offer in the window was **declined**, which `OfferLifecycle` does not fold into
   `lastAcceptResolvedAt`. *One possibility* is that the expanded frame arrived after `DASH_STOP`'s own
   grace had taken the region out from under the anchor (`lastAnnouncedPostTaskTaskId`); confirming
   would need a `SessionReplay` of that slice.

3. **The confirm-decline capability is UNGRANTED on this install** — 23 `Denied confirm_decline — no
   granted capability for rule 'doordash.screen.offer_popup_confirm_decline' (fail closed)` WARNs. Not
   a defect (the #843 no-auto-grant posture working as designed, its ninth corroboration), but it means
   #577 quick-decline auto-confirm has been off for the whole window and stays off until the consent
   prompt is answered. Worth telling the dev before the next dash — the new Compose accept capability
   from #1115 will also arrive **undecided**, so the prompt will have more than one row.

4. **A 0.6-second pickup dwell** (0.01 min) sits at the bottom of a 23-row dwell distribution whose top
   is 56.22 min. No known consequence; worth one glance at which job it belongs to before the #588
   shop-rate model reads it as a sample.
