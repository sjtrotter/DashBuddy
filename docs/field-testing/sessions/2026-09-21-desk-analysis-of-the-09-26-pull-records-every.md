## 2026-09-21 → 2026-09-24 (desk analysis of the 09-26 pull — #1118 records every decline and every accept it can see; eight accepts still died, one with $17.85; two H-E-B offers merged into one; support-chat removals leave phantom pickups; two new 8.99.20 name leaks)

**Date:** 2026-09-21 → 2026-09-24 (four dashing days) · **Platform(s) tested:** DoorDash (app **8.98.5**
through 09-23 ~15:05, then **8.99.20** — a NEW platform build that rolled out MID-DASH: the last 8.98.5
envelope is `chat_conversation/2026-09-23_15-03-36-147…`, the first 8.99.20 one is
`app_startup/2026-09-23_15-07-05-820…`; DashBuddy's process restarted at 15:06:29 with no log line of any
kind for the 71 s before it — *hypothesis:* the package update restarted our accessibility service —
and recovery replayed 3 observations and resumed `DoorDash:Online` mid-job with no live grace, which is
correct) · **Branch under test:** `master` at `71bacc8c` (build `0.230.0+71bacc8c`, built
2026-09-21T11:53:57Z, read off the logs per PR #1066) — the **first field run of PR #1118** (offer
outcomes on transition evidence). **PR #1125 (#1122/#1123) was NOT on the phone**; the phone was
re-installed on `1b596f74` after this pull (the debug reinstall resets the consent store again — the
dev must re-Allow the automations on first foreground). · **Field conditions:** eleven sessions — 690
(09-21, 3 drops, $76.31), 700 (09-21, 2, $55.34), 707 (09-22, 2, $45.50), 714 (09-22, 1, $14.65), 718
(09-22, a 22-second dash with one decline), 719 (09-23, 4, $82.31), 732 + 736 (09-23 afternoon,
`early_offline`, 0 drops — both REAL dashes whose one accepted order support took away, Bug 3), 739
(09-24, 2, $36.85), 746 (09-24, 1, $18.50), 750 (09-24, 1, $36.89). **67 offer cards: 18 accepts
(DoorDash's own `Offers accepted N/M` counters plus the two support-removed jobs), 48 declines, 1
untouched.** Two pauses (one hand-resumed), no force-stop, no in-app unassign, no return order, no
stacked job. Three process starts (09-21 06:54, 09-23 15:06 mid-dash, 09-25 08:29); every
`PipelineStats` line `restarts=0`, `mappingFailures=0`, `notifListenerDisconnects=0`; **zero ERROR
lines**; no `RecognitionHealth` / `LocaleBoundary` / `TtsHealth` notice. 1 389 capture envelopes (699
on 8.98.5 with 163 UNKNOWN, 405 on 8.99.20 with 100 UNKNOWN — recognition held across the platform
bump); log coverage continuous 09-21 06:54:33 → 09-25 20:49:15 (11 rotations + `app.log`). Pull dir
`~/dashbuddy/logs/2026/09/26`, device purged after verification; five raw-PII envelopes purged from the
pull, pseudonymized copies of the four rule-candidate renders at
`~/dashbuddy/fixtures-pending/2026-09-26/`. Desk report: `~/dashbuddy/logs/2026/09/26/DESK-ANALYSIS.md`.
**Money:** $366.35 reported, $348.50 recorded — ten of eleven sessions reconcile to the cent, session
739 is short exactly $17.85 (Bug 2).

### Bugs

1. **Two new Pledge leaks on DoorDash 8.99.20 — the customer's name on two UNKNOWN surfaces (#1127,
   #1128), plus two known recurrences.** (a) The "Can't hand order to customer" alternate-method page
   now personalises its first step: `com.doordash.driverapp:id/step_title` reads `Contact <First> <I>`
   (2 envelopes, 09-24 16:22:14 / 16:22:31). No rule claims the page; `step_title` is a generic step id
   (its siblings are DoorDash's own step vocabulary) so it is not on `ID_MARKERS`, and `Contact ` is a
   chrome-ambiguous prefix (`Contact support` is a sibling option) so `CustomerTextMarkers` cannot carry
   it. The 8.98.5 render of the same page (`cx_unavailable_steps_view`, 09-23 09:21) said `Contact
   Customer` with no name — **#1127**. (b) The pickup "What do you want to do next?" resolution sheet
   (`Ask the store to place the order` / `Unassign with no pay` / `Order not found in system`) renders
   the fused `For <First> <I>. • <store>` line raw (09-23 15:53:12) — the #809 fused-`For ` class. The
   recognized sibling `pickup_resolution_options` masks the identical line on its own render (`For
   [redacted:de13]`, 09-23 15:07:44), so the gap is the anchor vocabulary — **#1128**. (c) **#1116
   recurred** on 8.99.20 (09-23 15:49:33): the timeline detail sheet's second frame with a street,
   city/ST/ZIP and the quoted note raw; the name line was caught by the `Deliver to ` backstop. (d) The
   **#1122** header-less workflow sheet recurred once on 8.98.5 (09-23 11:30:13) — expected, the fix was
   not on the phone. All five raw files purged; the recognized `dropoff_workflow_sheet` envelope of the
   same job (11:38:20) is fully masked including a hashed bottom-bar name slot. The logs and
   `shareable.log` carry none of the leaked values (grep 0). No raw name in any RECOGNIZED envelope; the
   banking sweep over `captures/` is 0 for all three playbook greps.

2. **#1119 — eight of eighteen real accepts died (44 %), and for the first time one took its money with
   it.** #1118 did its half: the ten accepts it could see are `Accept inferred from the task surface`
   with a job behind each and zero over-inference. The eight it could not see all logged `OFFER_TIMEOUT`
   with a job minted behind them (…-697, -701, -715, -720, -729, -737, -740, -747). Two sub-shapes:
   six are the 09-20 transient-`waiting_for_offer` leave edge (three a plain "Looking for offers"
   frame, three a **HYBRID** frame whose idle header sits over a pickup card that is already painting
   `Delivery for` / `user_name` / `Directions` — the rule still classifies it idle), and **two are
   new: the accept came in the card's last 2–4 s and the `OFFER_EXPIRY` timer (anchored on
   `countdownExpiresAt`) fired ~2 s before the first pickup frame** (…-715 at +46.8 s vs pickup +48.5;
   …-740 at +46.5 vs +49.2) — fail-null by #1104's design, but it is an accept. Six were rescued by
   their receipt (`DROP_SHARE`), one (…-737) was removed by support, and **job …-740 (offer `e6d11317`,
   $17.85) lost its pay**: the drop was completed through the geofence-override / `Mark as delivered`
   path (`dropoff_handoff` → `dropoff_geofence_warning` → `dropoff_help_menu` →
   `dropoff_completed_confirm` → `dash_along_the_way`, 09-24 16:23:33 → 16:23:40) and **DoorDash rendered
   no receipt sheet at all** — 3 of 16 drops this window had no `delivery_summary_*` frame, all three on
   that path (708, 711, 740); 708 and 711 kept their money only because their accept was linked
   (`OFFER_PAY`). With no link there is no fallback: WARN 16:23:50.951 `#691 offer-pay estimate eligible
   but unsplit … ownOfferPay=null` → `payBasis NONE`. *Hypothesis:* DoorDash skips the per-delivery
   receipt on the Mark-as-delivered path (or in along-the-way mode) — unconfirmed. Comment on #1119.
   A `DELIVERY_ADJUSTMENT` for …-740 is the dev's call.

3. **#301 class through a new door — support-chat removal leaves a phantom `PICKUP_CONFIRMED` and a
   15-minute pickup dwell row, twice.** Sessions 732 and 736 each accepted one H-E-B shop order, reached
   the store, went `pickup_issue_menu` → `pickup_resolution_options` → support chat, and support removed
   the order (the dasher never took the in-app `Unassign with no pay` path). Each left seq 3167 / 3178
   `PICKUP_CONFIRMED` (`confirmedAt` = the `dash_along_the_way` frame after a `Missed Delivery` push at
   15:14:20, dwell 16.1 min; and the NEXT offer's presentation instant at 16:03:46 while we still showed
   the dasher at the store, dwell 14.5 min), each committed by a 10 s `GRACE_COMMIT` and emitted at the
   dash end; **zero `TASK_UNASSIGNED`**, no `JOB_ACCEPT_MISMATCH`, offer `d58fc055` left with
   `linkedJobId` NULL, ~18 odometer miles attached to no delivery. *Hypothesis:* no screen on this path
   carries an unassign anchor — the only artifacts are the push and the fresh offer. The 8.99.20 sheet
   where `Unassign with no pay` now lives is unrecognized (#1128). Comment on #301.

4. **#1069 fielded — two DIFFERENT H-E-B offers merged into one row, and the second was never
   spoken.** Offer `8cabdfe5` ($19.50) was HUD-declined at 09-21 14:52:14, its confirm sheet seen at
   14:52:15.9; every frame after it was flow-less (`side_nav_drawer`, `ratings`, UNKNOWN), so it stayed
   pending. A NEW offer `8a540c6e` ($20.50) arrived at 14:52:30.276 and was folded into it as an
   enrich-as-variant, because **all 48 H-E-B offers in the window share `presentationKey`
   `e30e80e8…`** (`sha256(store|orders.size|orderTypes)` is identical for every single-store H-E-B shop
   order). Ledger: `OFFER_RECEIVED 8cabdfe5 $19.50` (seq 3019) → `OFFER_DECLINED 8a540c6e $20.50` (seq
   3020, dt 27.4 s on the FIRST offer's `presentedAt`); session 700 counts 6 offers against DoorDash's
   7; the second got a chat card and a screenshot but **no TTS** (66 `speaking` for 67 `Rule LOG`
   receipts). Comment on #1069.

5. **Three real declines recorded as `OFFER_TIMEOUT` because a flow-less screen held the exit past the
   15 s sheet window** — the dev's "Dash Control" observation, characterized under *Field UX context*
   (two on `on_dash_map` at 16.71 s and 22.46 s; one on `navigation_generic`'s flow-less branch at
   20.75 s). Comment on #1104. Decline recall otherwise 44/48, none misread as an accept.

### Field UX context

- **The dev's observation, characterized.** "When dashing along the way and I decline an offer, it
  sometimes drops me into the Dash Control screen instead of back onto the map; it doesn't record
  accept/decline/timeout until I move back onto the map (Return to dash)." The screen is
  `doordash.screen.on_dash_map` (`dash-lifecycle.json5`, priority 110) — the side-menu home with the
  `This week` earnings pill, `You're dashing now`, a `Return to dash` button and the Home / Schedule /
  Account / Ratings / Earnings / Promos / Preferences / Help list (e.g.
  `on_dash_map/2026-09-23_15-32-49-226…973816`). Its `state` is `modeHint: online` only — **no flow** —
  so `OfferLifecycle.stepOffers` treats it (and the `side_nav_drawer` that follows) as inert, and the
  pending offer resolves on the first flow-bearing frame after Return to dash, which was
  `waiting_for_offer` every time (the `Dispatch` card posts ~30 ms after it). Five occurrences, every
  one a decline made during along-the-way navigation (`dash_along_the_way` → `navigation_generic` was
  the frame before each card): sheet→exit 8.19 s, 11.14 s, 11.40 s (recorded DECLINED) and **16.71 s,
  22.46 s (past `declineSheetWindowMs` = 15 s → `OFFER_TIMEOUT`, HUD "Offer Timed Out!")**. A sixth
  decline went the same way through `navigation_generic`'s flow-less **branch 1** (a frame carrying
  `exit` but no `min`/`mi`, so not branch 0's `flow: idle`): 20.75 s → TIMEOUT, where a full branch-0
  nav frame four minutes earlier resolved a decline in 2.2 s. No `Return to dash` click envelope was
  captured (a Compose control). The same flow-less dwell is what let Bug 4's second offer land on a
  still-pending predecessor. *Possibilities, hypotheses only — the dev has said they are not sure they
  want anything changed:* (a) the window is anchored on the LAST sheet sighting rather than on the
  card's disappearance, so any flow-less dwell after the sheet eats into it; (b) Dash Control / nav
  branch 1 could carry a flow, but that is a state-machine change riding on an offer-outcome fix;
  (c) #1119's evidence-linking design (DECLINED only on direct evidence, NOT_TAKEN at dash end)
  changes this whole path anyway. Why DoorDash lands on Dash Control only sometimes is not visible in
  the data — other along-the-way declines returned to nav normally (09-22 19:52–19:56).
- **The dev's "some declines now produce click envelopes" is not borne out by the pull.** Exactly one
  decline-button click envelope exists (`accessibility.click/initial_decline/2026-09-22_19-51-05-598…`,
  offer 3801fdbc), and it landed **216 ms after DashBuddy's own automated `decline_offer` tap**
  (`Single verified candidate … clicking it` at 19:51:05.366) — *hypothesis:* the event from our own
  `performAction`, not a human tap. Zero confirm-button clicks, zero Accept clicks. Every recorded
  outcome this window came from #1118's transition inference.
- **The confirm-decline capability is still un-granted:** 49 × `Denied confirm_decline — no granted
  capability (fail closed)`, one per sheet sighting. And the reinstall on `1b596f74` reset the store
  again — re-Allow on first foreground.
- **DoorDash 8.99.20 rolled out mid-dash on 09-23** and recognition held (UNKNOWN share 23 % → 25 %).
  The first 8.99.20 `pickup_pre_arrival` after an accept produced one `ParseHealth` WARN ("all 5
  extractable fields unresolved", 09-22 18:00:01 — actually still 8.98.5; a pre-render frame).

### Verification (desk)

- **#1118 / #1104 transition outcomes — CONFIRMED 1/2** on its first run: 10/10 `OFFER_ACCEPTED` are
  task-surface inferences with a job behind each (no over-inference); 44/44 `OFFER_DECLINED` are
  confirm-sheet inferences, all inside the window (median ≈ 5 s, max 11.40 s); the one untouched offer
  (`74695b74`, 09-21 10:03) is a genuine `OFFER_EXPIRY` timeout at +45.1 s. Residuals are Bugs 2, 4, 5.
- **#1114 Compose offer card — CONFIRMED 2/2, RETIRED (moved here).** 67 cards → 67 `Rule LOG
  [OFFER_RECEIVED]` on BOTH 8.98.5 and 8.99.20; 66 `offer_records` (one merged, Bug 4 — a separate
  #830 defect); zero parse nulls on pay / distance / merchant / est. time / score / action / quality;
  two-store offers name both stores (`CVS & McDonald's`); `parseShortfall{offer_popup…}` absent; 30
  offer-shaped UNKNOWN frames are all Accept-less half-renders inside a recognized offer's window; the
  two `side_nav_drawer` frames carrying `Decline` + a route summary are decline-transition overlays of
  already-recognized offers; 54 `NEW_ORDER` lines.
- **#1102 receipt auto-expand (retired 09-21) — re-observed clean:** 14 `Performing expand_earnings`,
  11 `Single verified candidate … clicking it` first try + 1 on a retry; at 09-24 20:26:23.027 a `Could
  not find any live node` was **followed 543 ms later by a successful tap** — the #1102 throttle
  restore working in the field; 0 `Throttled`; one `NONE passed verification` (09-21 15:51:22) where the
  receipt was itemized anyway (`DROP_SHARE`).
- **#1120 / #1033 layer 2 — not exercised, no damage:** 13 `DELIVERY_RECEIPT_REPRICE` events, all
  single-drop single-offer jobs, all `already holds … — no-op`, `receiptRepricedAt` NULL everywhere; no
  stacked job in the window.
- **#1103 — clean negative (3rd):** 0 `receipt running-total read refused`; the three zero-drop sessions
  report NULL; the 3 `SESSION_PAY_SETTLE` fires each inside their own session; back-to-back starts
  707→714 (38 s) and 746→750 (20 s) each carry only their own total.
- **#1108 — clean, not a confirmation:** `Σ realizedMiles ≤ span` on every session (largest gap 2.44 mi,
  session 707), zero negative `realizedMinutes`, no stacked job.
- **Decline target bound (8.97.8+) — bind half holds a second time** (33 × `Single verified candidate
  for decline_offer`, 0 `No 'declineButton' target bound`, no `offer_popup` bindShortfall); confirm half
  still unmeasured (un-granted).
- **#1063 — desk half clean a 3rd time** (67 cards, one presentation each, zero `Replaced by new
  offer`/`Superseded`, `accept_decline_footer_container` in 0 UNKNOWN frames).
- **#1058 — partial:** the alcohol pre-arrival card (`alcohol_dropoff_ic_scan`) claimed as
  `dropoff_pre_arrival` 4× with no raw PII; the workflow sheet recognized + masked once, its header-less
  frame leaked again (#1122, fix not on the phone).
- **#1059 — not exercised** (0 scanner ids tree-wide); adjacent positive: `sensitive.id_verification`
  dropped 39 frames across the two alcohol drops, no scanner frame reached disk; `sensitive.red_card` 0
  on Red-Card shop orders (no over-match).
- **Unexercised:** #1122/#1123 (build predates the fix), #1107 (no `dropoff_step_instructions` folder
  and no `description_text_view` node on either DoorDash build — the wrapper may be gone), #998 (the
  "Can't hand order" page rendered twice with its `Return the order to merchant` step, but both drops
  completed normally), #1121 (no `+$` / `+N stop` text anywhere), #1054 grace-restart (one mid-dash
  restart with no live grace — `Recovery re-armed` correctly absent; summary ends 2.501–2.513 s,
  early-offline ends 10.002–10.004 s) and #1054 pause-relaunch (two `DASH_PAUSED`, one
  `MODE_RESUME_COMMIT` 09-24 16:25:35, no restart mid-pause), #301 deliberate path (Bug 3 is the
  support-chat variant).
- **Censuses.** WARN by tag: `Effects` 83 (49 confirm-decline denials, 29 `GRACE_COMMIT` by design, 1
  `MODE_RESUME_COMMIT`, 4 expand-tap lines above); `Pipeline` 31 (16 `Capture backstop: UNKNOWN screen
  carried customer PII` — `Pickup for ` ×7, `user_name` ×7, `Deliver to ` ×3, `address_line_2` ×1; 13
  `Capture scrubbed` — DasherDirect ×4, Transfer-out ×4, Crimson ×3, `shape:` ×2 — all on the four
  post-dash cash-out visits); `ParseHealth` 9; `Odometer` 5 (4 POOR_ACCURACY 69–419 m, 1
  IMPLAUSIBLE_SPEED 09-22 17:55:50, each recovered); `StateMachine` 1 (Bug 2's #691 line). ERROR 0.
  Sensitive gate drops (DEBUG): dasher_direct 406, catchall 37, id_verification 39, balance 18,
  crimson_balance 10 — all at cash-out moments or the alcohol ID scans. `shareable.log`: 0
  `[scrubbed:…]`. TTS 66 `speaking`, 0 losses. 16 `DELIVERY_COMPLETED` = 16 rows, one per job; no
  doubled drop, no `JOB_ACCEPT_MISMATCH`. parseShortfall vs the benign baseline: `waiting_for_offer`,
  `dash_along_the_way`, `idle_map`, `pickup_shopping` as documented; off-baseline singles
  `pickup_pre_arrival` ×1 (the pre-render frame above) and `bindShortfall{ratings#ratingsHubContent=1}`
  (off-dash 09-22 08:32). UNKNOWN families (263 window frames): 30 offer half-renders (#595), 24
  text-free skeletons (9 of them `drop_off_workflow_host_fragment`), ~23 support-flow **Order details**
  item sheets (`Close sheet` + `Drag handle` + `N items` + `N x` rows, no PII), 16 acceptance-rate /
  offer-history pages (no PII), 9 prism bottom sheets, 7 `This dash so far / Total online time`
  sheets, 7 nav-container frames, 6 `Current dash / Pick up by` task cards (name scrubbed by the
  `user_name` backstop), 6 `Shop and Deliver` headers, 5 `by HH:MM • H-E-B` timeline rows (`Pickup for `
  backstop fired), 3 `Did we resolve your issue?`, 3 `Where did you find it?`, 3 `Can't hand order to
  customer` (Bug 1a), 1 resolution sheet (Bug 1b), 1 #1116, 1 #1122. Click UNKNOWN (188) is
  `Confirm`/`Take photo`/`Scan again`/`Continue`/`This offer`/`End dash`/nav-step taps.

### Open questions

- **The decline window after a flow-less exit** (Bug 5 / Field UX): anchor on card-vanish, give
  `on_dash_map` and nav branch 1 a flow, or leave it to #1119's evidence-linking? The dev has not
  asked for a change; the comment on #1104 lists the options.
- **`DELIVERY_ADJUSTMENT` for job …-740** ($17.85, receipt-less Mark-as-delivered path, Bug 2)?
- **The receipt-less completion path** — does DoorDash really skip the per-delivery receipt on the
  geofence-override / Mark-as-delivered path, or only in along-the-way mode? Three sightings, all
  along-the-way. If confirmed, the offer link is the ONLY pay source on that path (see #1119).
- **Rule candidates from the census** (no PII unless noted): the "Can't hand order to customer" page
  (#1127, PII), the pickup resolution sheet (#1128, PII — and the natural anchor for #301's deliberate
  path), the support-flow Order-details item sheet (23 frames), the acceptance-rate / offer-history
  pages (16), the `This dash so far` progress sheet (7).
- **Is the "Drop off steps" wrapper (#1107) gone on 8.98.5+?** Two pulls without a single render.

### Meta

- DoorDash 8.99.20 arrived mid-dash and the process restarted with it (no log for 71 s, then a clean
  recovery with 3 replayed observations). The corpus has no 8.99.20 fixtures yet; the intake of this
  slice is the next corpus PR.
- Consent reset on every debug reinstall remains a per-dash chore for the dev (4 automations to
  re-Allow); the 49 confirm-decline denials this window are the cost of the confirm half staying
  un-granted.
