## 2026-09-20 (desk analysis of the 09-21 pull — the Compose offer card works perfectly and four real accepts still died; a receipt re-priced a drop it had never described; two Pledge leaks)

**Date:** 2026-09-20 · **Platform(s) tested:** DoorDash (app **8.98.5** — a NEW platform build; the
09-16→19 window was 8.97.8, so every reading below is against a tree nothing in the corpus has seen) ·
**Branch under test:** `master` at `aab8d960` (build `0.230.0+aab8d960`, built 2026-09-20T14:02:35Z,
read off the logs per PR #1066) — the **first field run of PR #1115**, the Compose offer-card
recognition fix. **PR #1118 (offer outcomes on transition evidence) was NOT on the phone**, so every
accept and every decline logs `OFFER_TIMEOUT`; that is the known pre-#1118 state, not a new defect. ·
**Field conditions:** one long DoorDash day — session **661** 09:08:52 → 16:40:25 (`summary_screen`,
$215.12 reported, 8 drops, 71.00 mi span) plus `early_offline` **685** and evening session **686**
($31.37, 1 drop); the log window opens on 09-19's sessions 656/660, already covered by the previous
entry. **22 offer cards, 10 accepts / 12 declines** (DoorDash's own dash counter is the oracle — see
Bug 1). Uber Offline throughout; one pause (hand-resumed), no force-stop, no unassign, no return
order, one stacked job (…-674); **one process for the whole day**, `restarts=0`,
`mappingFailures=0`, `notifListenerDisconnects=0`, **zero ERROR lines**, no `RecognitionHealth` /
`LocaleBoundary` / `TtsHealth` notice. 715 capture envelopes; log coverage continuous 09-20 09:03:09 →
22:02:13 (5 rotations + `app.log`). Pull dir `~/dashbuddy/logs/2026/09/21`, device purged after
verification; the phone was re-installed on `71bacc8c` (the PR #1118 merge) afterwards. Desk report:
`~/dashbuddy/logs/2026/09/21/DESK-ANALYSIS.md`.

### Bugs

1. **A real accept dies on ONE intermediate `waiting_for_offer` frame — four of ten accepts lost, and
   a phantom accept minted a job from an offer that was never taken.** DoorDash's own counters are the
   ground truth for the day: the receipt sheets read *Offers accepted 4 out of 5* (12:36:50) → *5 out
   of 9* → *6 out of 11* → *7 out of 12*, and the dash summary closes on **8 out of 16** ($215.12,
   7 hr 31 min). Session 661 therefore had exactly **8 accepts**, and all eight are independently
   corroborated: each maps 1:1 onto a `DELIVERY_COMPLETED` row and onto one shopping list whose
   `PICKUP_CONFIRMED.itemsShopped` (18/24/22/24/27/34/16/32) equals that card's `Shop for N items`.
   The machine attached **4** of them. What it did instead:

   | offer | $ | truth | machine |
   |---|---|---|---|
   | `0b2f12bc` | 27.84 | accept | **lost** — job …-668 minted with `jobOfferHashes=[]` |
   | `f54e4b64` | 11.50 | **not** accepted | **phantom** — minted job …-674 + a dropoff placeholder |
   | `137a3054` | 20.50 | accept (mid-job add-on) | **lost** — `OFFER_TIMEOUT`, `acceptedOffers` never grew |
   | `1c58c4aa` | 43.21 | accept | **lost** — job …-682 minted with `jobOfferHashes=[]` |
   | `75704d33` | 31.37 | accept (session 686) | **lost** — job …-687 minted with `jobOfferHashes=[]` |

   *Hypothesis for the mechanism:* `OfferLifecycle.resolveOnLeave` mints the accept survivor only on
   the ONE observation that leaves offer-presentation, and only when that observation's flow is a task
   flow. On 8.98.5 the post-card sequence is sometimes `offer_popup → waiting_for_offer →
   pickup_pre_arrival` instead of `offer_popup → pickup_pre_arrival`; the transient
   `waiting_for_offer` frame is then the leave edge, its destination is not a task flow, the pending
   offer is resolved away, and the pickup surface arriving a fraction of a second later finds no
   survivor to mint. The measured lag between `OFFER_TIMEOUT` and the following `PICKUP_NAV_STARTED`
   separates the two populations cleanly — **0 ms (the same observation) on all six attached offers**,
   against −22 / +53 / +66 / +931 ms on the four lost ones. Three log sequences show the intermediate
   frame directly: 11:05 `offer_popup → waiting_for_offer → Offer Timed Out! → pickup_pre_arrival`;
   15:36 `offer_popup → UNKNOWN → waiting_for_offer → pickup_pre_arrival`; 20:17 `offer_popup →
   UNKNOWN → side_nav_drawer → offer_popup → waiting_for_offer → pickup_pre_arrival`. The machine
   confesses the consequence itself on each lost job — `#997 offer-pay attribution degraded to
   JOB_POOLED … 0 offer(s) over 1 drop(s)` ×2 and `#691 … denominator=1 of 1 owed, ownOfferPay=null,
   jobOfferTotal=null` ×2. **Money:** $59.21 folded `payBasis NONE` / $0.00 across jobs …-668 and
   …-687 ($80.24 counting 09-19's job …-657, $21.03, the same shape). *A second hypothesis, separate
   from the first:* the phantom is the inverse edge — `f54e4b64` timed out at 12:38:43.732 and
   `PICKUP_NAV_STARTED job …-674` landed in the **same millisecond**, with no job running (job …-671
   had closed at 12:36:53), so a leave-edge that lands on a task flow appears to be accepted whether
   or not anyone pressed Accept. Both would need a `SessionReplay` of the 11:04 / 12:38 / 13:51 slices
   to confirm. Neither shape is one PR #1118 covers: its `!offer.returnFlow.isTaskFlow()` guard fires
   on the return flow, and all three mid-job offers here were logged `returnFlow=Idle` (R0 had fallen
   back to `waiting_for_offer` between the drop and the card).
   - **Status:** Open (#1119).

2. **The #1033 layer-2 re-price applied a single-drop receipt to every drop of the job and destroyed
   $28.51.** Job …-674 is the day's one stacked job, and its two drops were two different orders:
   drop 1 shopped 27 items and completed at 13:51:04 against its **own** receipt ($28.51, folded
   `DROP_SHARE $28.51`, `Saved: $28.51`); drop 2 shopped 34 items and completed at 14:40:39 against
   **its** own receipt ($20.50), whose expanded sheet says in DoorDash's own words *"This offer …
   $20.50"* — one drop. At 14:40:39 the log reads `#1033 receipt re-price: job …-674, **2 drop(s)**,
   receipt 2050¢`, and two `DELIVERY_RECEIPT_REPRICE` events set **both** drops to $10.25. The job
   earned $49.01 and is recorded at $20.50. `receiptRepricedAt` on row 2891 is the **first non-null in
   the corpus** — the first time layer 2 has ever applied rather than no-op'd, and the first
   application was wrong. *Hypothesis:* a `ReceiptCoverage` failure of exactly the class #1073 names —
   "a receipt speaks only for the drops it described when read" — where the coverage intersection did
   not exclude a sibling drop that had already folded its own receipt. Worth noting that the
   playbook's `rows_total == receipt_total` guard **passes** here (20.50 == 20.50) because it compares
   against the LAST receipt; the invariant is blind to a job whose earlier receipt was superseded.
   - **Status:** Open (#1120). (Moved here from the checklist — the #1033 layer-2 item was found
     **BROKEN** on this pull and is deleted from `Next field test`.)

3. **PLEDGE — the 8.98.5 dropoff customer-detail sheet falls to UNKNOWN on its un-anchored second
   frame and ships everything raw.** `dropoff_pre_arrival` claimed and correctly masked the sheet's
   FIRST frame at 15:09:16 (`Deliver to [redacted:…]` present); 17 s later the same sheet re-rendered
   **without** the `Deliver to` header, no rule matched, and it went to disk whole:
   `UNKNOWN/2026-09-20_15-09-33-393__…510649.json` (plus its twin `…-614__…9775b7.json` and a partial
   third at 15:27:58) carries a street line, a City/ST/ZIP line, an `Apt` unit number, a quoted
   customer instruction body containing a **gate code, twice**, and the customer's name in the bottom
   bar. Every node on that frame is id-less, so `ID_MARKERS` could not help; `CustomerTextMarkers`
   could not help (no `Deliver to` / `Delivery for` prefix survived); and the name is rendered
   **ALL-CAPS** as a first name + last initial, a form `FIRST_LAST_INITIAL_PATTERN` does not match.
   **This is a recurrence of the #1116 / #985 class** — the same "rule claims the first beat, the
   fully-inflated second beat drops the anchor and ships raw" failure, on a different sheet one pull
   later. *(The bare 3-digit number also on that sheet is DoorDash chrome — the same glyph appears
   beside `Customer tips` in `delivery_summary_expanded`. Not a code.)* The files are purged from the
   pull and the device was already purged, so no copy remains.
   - **Status:** Open (#1122).

4. **PLEDGE — `dropoff_pre_arrival`'s redact misses the bottom-bar customer-name node.**
   `dropoff_pre_arrival/2026-09-20_12-35-05-218__…8cc2d1.json` is a **recognized** envelope whose
   every other PII slot grades correctly (`Deliver to [redacted:a3a3]`, two more hashed values, three
   plain `[redacted]`) — and whose bottom-bar `TextView` carries the customer's first name + last
   initial **raw**. The identical structural slot is masked `[redacted:fa2f]` in
   `dropoff_workflow_sheet` and held the ALL-CAPS name of Bug 3 in the UNKNOWN frame, so the node
   exists across all three surfaces and only this rule's `redact` omits it. It is the only one of the
   14 `dropoff_pre_arrival` envelopes in which that node rendered. Per CLAUDE.md a recognized rule
   that forgets to redact has **no backstop**, which is why this is the same severity as Bug 3 despite
   being one node. Purged.
   - **Status:** Open (#1123).

5. **The mid-job ADD-ON offer card is not recognized on 8.98.5 — the dasher was shown +$10.50 and the
   app was blind to it.**
   `side_nav_drawer/2026-09-20_15-51-37-652__doordash__accessibility.window__side_nav_drawer__7f17fd.json`
   — two H-E-B store legs with their shop counts, `Customer dropoff` ×2, `Accept`, `Decline`, `0:45`,
   and the two lines that break it: the money is a **delta** (`+$10.50`, not `$10.50`) and the route
   summary is a **delta** (`+1 stop (1.5 mi) • +1 min`, not the `N stops (N mi) • N min` shape
   `offer_popup` requires). The frame therefore fell through to `side_nav_drawer`, and the dev got the
   `New Order: Go to H-E-B and 1 other store` push at 15:51:36, was shown a two-store add-on worth
   +$10.50 on top of a live job, and declined it blind at 15:51:52 with **no `OFFER_RECEIVED`, no
   evaluation, no bubble and no voice**. State-machine impact: none — no pending offer was ever
   created — so this is a **blindness** defect, not a corruption one. It is the only `+$` / `+N stop`
   card in the pull, and the only one of 41 `side_nav_drawer` captures that contains offer content.
   - **Status:** Open (#1121).

### Verification (desk)

1. **Money: $246.49 reported, $157.77 recorded, $88.72 (36 %) unattributed — and every dollar of the
   gap is Bug 1 or Bug 2.**

   | gap | $ | cause |
   |---|---|---|
   | job …-668 drop | 27.84 | accepted offer never attached → `payBasis NONE` (Bug 1) |
   | job …-674, 2 drops | 28.51 | the 2nd receipt re-priced BOTH drops (Bug 2) |
   | job …-687 drop | 31.37 | accepted offer never attached → `payBasis NONE` (Bug 1) |
   | residual, session 661 | 1.00 | unmodelled (peak pay / challenge) |

   Counting 09-19's session 656 (job …-657, the same lost-accept shape) the four-session total is
   $267.52 reported / $157.77 recorded. payBasis mix for 09-20: `DROP_SHARE` 5 rows $104.56 ·
   `OFFER_PAY` 2 rows $53.21 · **`NONE` 3 rows $0.00**. Invariants that DO hold:
   `Σ realizedMiles ≤ (lastOdometer − startOdometer)` on every session (largest gap 0.32 mi), **zero**
   negative `realizedMinutes`, and `rows_total == receipt_total` on all four receipt-priced jobs —
   the last of which is itself misleading here (Bug 2).

2. **(A) #1114 / PR #1115 — the Compose offer card is CONFIRMED (1/2), cleanly, with one new gap.**
   **22 offer cards → 22 `OFFER_RECEIVED` → 22 `offer_records` rows → 22 TTS utterances → 22
   screenshots → 22 chat cards → 22 resolutions**, a perfect 1:1 against two whole blank days on the
   previous pull. **Parse quality: zero nulls** — every row carries `payAmount`, `distanceMiles`,
   `itemCount`, `merchantName`, `estTimeMinutes`, `score`, `action` and `quality`; no blank store and
   no ghost merchant (`2 stops (…)` never leaked into `merchantName`); 21 rows `H-E-B` and 1
   `Taco Bell` (the only non-shop offer, `itemCount 0` — correct).
   `parseShortfall{doordash.screen.offer_popup…}` is **absent entirely**, the only shortfall entries
   being the documented benign baseline (`waiting_for_offer=35`, `pickup_shopping=6`,
   `dash_along_the_way=5`). **Both decline binds are back at 0** —
   `bindShortfall{…offer_popup#declineButton}` and `{…offer_popup_confirm_decline#confirmDeclineButton}`
   were 2 and 25 on the previous pull; 8 `Single verified candidate for decline_offer on doordash
   [doordash.screen.offer_popup] — clicking it` lines fired and the confirm sheet renders a real
   `Decline offer` node again on 8.98.5. The **`New Delivery!` push logs `NEW_ORDER` again** — 23
   `Rule LOG [NOTIFICATION_RECEIVED]: NEW_ORDER`, 4 `notification/new_order/` envelopes, on the new
   `…nexus-new-offer-no-sound-haptics` channel. Offer-shaped `UNKNOWN` frames: 4, all half-renders of
   a card recognized on an adjacent frame (the permitted #595 family);
   `accept_decline_footer_container` appears in **zero** UNKNOWN frames. The one gap is Bug 5 — the
   add-on card's delta shapes.

3. **#1102 CONFIRMED a second time — RETIRED at 2/2 (moved here from the checklist).** **5 receipts
   presented, 5 expanded** — `Performing expand_earnings` ×5 and `Single verified candidate …
   clicking it` ×5 — with **zero** `Could not find any live node for: expand_earnings` this time (the
   two mid-slide failures of the previous pull were the shape the fix rescues; here the tap landed
   first time on every receipt). The two `Throttled action` lines both land ~500 ms **after** a tap
   that had already succeeded, which is precisely the restored behaviour: a landed tap keeps its
   stamp, a failed one does not. 5 of the 8 delivery rows folded `DROP_SHARE`; the 3 `NONE` rows are
   Bug 1, not this item.

4. **#987 CONFIRMED a second time — RETIRED at 2/2 (moved here from the checklist).** Both
   `notification/earnings_deposit/` envelopes read `{"title": "[redacted]", "text": "Your Dasher
   earnings for [redacted]", "tickerText": …, "bigText": …}` — **plain, no hex, all four flat
   fields**. The playbook's dasher-banking sweep returns **0** on all three grep families tree-wide.

5. **#1063 — sixth consecutive clean desk pass.** 22 → 22 → 22 → 22 → 22 (cards → `OFFER_RECEIVED` →
   utterances → screenshots → resolutions), zero `Replaced by new offer` and zero `Superseded by
   direct offer`, `parseShortfall{offer_popup}` absent. Dev-eyes half still open.

6. **#1058 — PARTIAL PASS with a regression.** The two `dropoff_workflow_sheet` envelopes are graded
   correctly (hashed name, **plain** `[redacted]` for address, unit and note) — that half holds on
   8.98.5. But the adjacent-positive half that has carried this item for five pulls **broke**:
   `dropoff_pre_arrival` shipped a raw customer name in its bottom bar (Bug 4), and the same sheet's
   un-anchored second frame shipped everything (Bug 3). The alcohol arrival card (leak A of the
   original #1058) still has not rendered.

7. **#1104 / PR #1118 — UNEXERCISED, and this pull is its baseline.** The device build predates
   #1118, so all 22 offers logged `OFFER_TIMEOUT` against a ground truth of 10 accepts + 12 declines
   (the 12 declines each carry a `Denied confirm_decline` WARN or an `OfferActionReceiver:
   decline_offer` line — i.e. they reached the "are you sure" sheet). The next pull, on `71bacc8c`,
   is the first that can measure it. Bug 1 names a shape #1118 will **not** convert: an accept whose
   leave edge is a transient `waiting_for_offer` frame never reaches the transition #1118 reads.

8. **#1103 UNEXERCISED, clean negative.** `settle gate: receipt running-total read refused` = **0**
   hits — the causing shape did not recur and there was no *false* refusal either. Both
   `early_offline` zero-delivery sessions (660, 685) report NULL `reportedEarnings`; no
   `Delivery - <amt>.png` / `DeliveryBreakdown - <amt>.png` pair straddles a dash boundary; no
   `DELIVERY_RECEIPT_REPRICE` is attributed to a following session.

9. **#1054 UNEXERCISED for a SIXTH consecutive pull, both items, baseline healthy.** Three summary
   ends at 2.502 / 2.504 / 2.504 s and two early-offline ends at 10.003 s; `restarts=0` and no
   `Recovery re-armed` (correctly — one process all day, nothing to restore). The day's one pause
   (11:52:50, `remainingText 34:58`) was hand-resumed two seconds later and committed by
   `MODE_RESUME_COMMIT` at 11:53:06 → `Session grace resume`: the ordinary path, not the force-stop
   exercise. Only the two on-dash exercises can move these.

10. **#1107 UNEXERCISED (second pull running).** The `dropoff_step_instructions/` folder is **absent**
    — the 8.97.8 "Drop off steps" wrapper page did not render at all on 8.98.5 this dash, so neither
    the recognition half nor the `plainMask` half was touched.

11. **#1059 UNEXERCISED for a sixth pull, over-match check passing a sixth time.** The four ids return
    **0** tree-wide and none of the three new arms fired. 175 `pickup_shopping` + 70 `shopping_item`
    Red-Card shop frames produced **zero** `sensitive.red_card` drops. Pre-existing arms healthy:
    `dasher_direct` ×73, `catchall` ×7, `balance` ×5, `crimson_balance` ×2.

12. **#1108 CLEAN, not a confirmation.** `Σ realizedMiles ≤ span` on every session, zero negative
    `realizedMinutes`, and the one stacked job (…-674) shows legs 7.68 + 6.22 = 13.90 mi, both
    non-zero — the causing shape simply did not recur.

13. **#996/#997 degraded exactly twice, and both degrades are the machine reporting Bug 1 honestly.**
    2 × `#997 offer-pay attribution degraded to JOB_POOLED … 0 offer(s) over 1 drop(s)` + 2 × `#691 …
    denominator=1 of 1 owed, ownOfferPay=null, jobOfferTotal=null`, all four on the lost-accept jobs
    …-668 and …-687. Shapes (b) sub-pooled and (c) consolidated-customer remain unseen.

14. **WARN / ERROR census: 0 ERROR lines, `restarts=0`, one process for the whole day.** The census:
    13 `Timer Expired: GRACE_COMMIT` (designed commit path), **12 `Denied confirm_decline … (fail
    closed)`** (Open questions 3), 5 + 1 + 1 + 1 `Capture scrubbed: UNKNOWN screen / notification /
    click hit marker`, 2 `#691 … unsplit` (Verification 13), 2 + 1 `Capture backstop: UNKNOWN screen
    carried customer PII` (`nodeId=user_name` ×2, one text marker), 3 known-benign `ParseHealth`
    parse-shortfall lines (`waiting_for_offer` / `pickup_shopping` / `dash_along_the_way`), 1
    `delivery_summary_collapsed optional bind 'expandButton' resolved no node (#1093)`, 1 `Odometer
    fix rejected (POOR_ACCURACY) delta=26m dt=3580ms`, 1 `Timer Expired: MODE_RESUME_COMMIT` (the
    11:52 pause). `unknownScrubbed=8`, `unknownCustomerScrubs=3`.

15. **UNKNOWN census: `unknownDropped=213`; 82 window + 99 click + 5 notification envelopes written.**
    Heads: 26 text-free Compose loading/transition skeletons, 8 single-string Compose chrome frames,
    6 side-menu / top-bar strips, **4 offer-card partial renders** (`Decline` + route line, no
    `Accept` / disclaimer — the permitted #595 family), 4 post-decline "This dash so far" sheets, 4
    nav-map chrome, 3 offer cards composited over the side-nav drawer, **3 dropoff customer-detail
    sheets (Bug 3)**, 3 dash-menu / timeline sheets, 3 support-chat surveys, 3 drop-off photo steps, 3
    camera sheets, 3 weekly-earnings screens, then one-offs. **Clicks: 99 UNKNOWN against 26
    classified** (`arrived_at_store` 9, `take_photo` 10, `open_chat` 7, `resume_dash` 1) and **zero**
    classified `accept_offer` / `decline_offer` / `confirm_decline` — consistent with the standing
    finding that a human tap on a Compose control emits no `TYPE_VIEW_CLICKED`. Notifications: 4
    `new_order`, 13 `customer_message`, 2 `earnings_deposit`, 1 each `marketing_promo` /
    `demand_nudge` / `dash_status_ongoing`, 5 UNKNOWN (marketing / demand nudge / a dash-paused push
    carrying the dasher's own first name, which is allowed).

16. **PII sweep of all 715 envelopes: two real leaks, both filed, both purged; everything else clean.**
    The sweep walked `text` / `desc` / `stateDescription` / `hintText` and the flat notification
    fields for two-capitalised-word names, the `[A-Z][a-z]+\s{1,4}[A-Z]\.` shape, `Pickup for` /
    `Deliver to` / `Delivery for` / `For <Name>`, `pin|gate code|entry code \d{3,}`, street addresses,
    `Apt|Unit|Suite \d+`, `City, ST 12345` and `Customer Notes`. Dasher-banking greps: **0 / 0 / 0**
    tree-wide. The two findings are Bug 3 and Bug 4. Clean everywhere else:
    `dropoff_workflow_sheet`, `timeline` (both envelopes fully masked — the #1116 shape did **not**
    reproduce on `timeline` itself, the class simply moved to a different sheet), `earnings_deposit`
    and `customer_message` all grade correctly; the 99 click envelopes are clean (the only oddity is
    Google-Nav `primaryManeuverText` road names without house numbers, which are not addresses); the 5
    UNKNOWN notifications are clean. The live backstop **fired 3×** — `Capture backstop: UNKNOWN
    screen carried customer PII` (two on `nodeId=user_name`, one on a text marker) — plus 8 marker
    scrubs, which is the privacy layer working on frames no rule claimed.

17. **Not exercised:** #998 (no refused or returned delivery); **#301** (the dev did reach
    `pickup_select_issue` → `pickup_resolution_options` at 12:38:53 but took **Contact support**, not
    "Unassign order" — zero `TASK_UNASSIGNED`, zero phantom `PICKUP_CONFIRMED`, so not a test of
    #301); #1034 (`grep -c '\$-' shareable.log` = 0, no window went negative); #967; every Uber item;
    every dev-eyes UI item. **#991 no-regression** — 22/22 `speak()` succeeded, zero `-1`, zero
    re-init, zero notice.

### Open questions

1. **PR #1118's guard is correct and does not cover any shape this dash produced.** Three offers were
   presented while `activeJob != null`: `0b2f12bc` (11:04:45, job …-665 still open — a real accept
   that was lost), `d455aa04` (13:51:04, `returnFlow=PostTask` — a decline the machine handled
   perfectly: no phantom, no placeholder) and `137a3054` (13:51:24, job …-674 still open — a real
   add-on accept that was lost). #1118's `!offer.returnFlow.isTaskFlow()` fires on the offer's
   *return* flow, and both mid-job accepts were logged `returnFlow=Idle`; the one phantom
   (`f54e4b64`) also had `returnFlow=Idle` and **no job running at all**. *One possibility* is that
   R0 falling back to `waiting_for_offer` between a drop and the next card is what makes
   `returnFlow=Idle` on a genuinely mid-job offer — which, if it holds, is the same root as Bug 1 and
   would mean the return-flow guard and the leave-edge tolerance are two views of one problem.
   Confirming needs a replay, not a log read.

2. **The playbook's `rows_total == receipt_total` money invariant is blind to a superseded receipt.**
   It compares each job's rows against the LAST figure the machine stated for that job, so job …-674
   passes (20.50 == 20.50) while $28.51 is missing. Worth deciding whether the check should compare
   against the Σ of *distinct* receipts seen for a job, or whether `ReceiptCoverage` should be
   asserted directly — either way the desk-validation playbook's step needs a note so the next pull
   does not read a green invariant as a clean job.

3. **The confirm-decline capability is UNGRANTED on this install** — 12 × `Denied confirm_decline —
   no granted capability for rule 'doordash.screen.offer_popup_confirm_decline' (fail closed)`. Not a
   defect (the #843 no-auto-grant posture working as designed, its tenth corroboration), but it means
   #577 quick-decline auto-confirm was off for the whole day and stays off until the consent prompt is
   answered. **Dev action:** re-Allow it in the consent prompt or Settings → Data & Privacy →
   Automation & Consent — the phone was re-installed on `71bacc8c` afterwards, so the prompt is
   waiting with this row (and #1115's Compose accept row) undecided.

4. **`FIRST_LAST_INITIAL_PATTERN` does not match an ALL-CAPS name.** Bug 3's bottom-bar name reached
   disk partly because the intake name shape expects `Firstname I.` casing. *Hypothesis:* an ALL-CAPS
   arm on the name shape is a rules-independent improvement that would have caught this frame even
   with no rule claiming it — but it widens a scrub layer, and per CLAUDE.md widening a scrub layer
   must never move a classification, so it wants its own look rather than being folded into the rule
   fix.
