## 2026-09-10 → 2026-09-12 (desk analysis of the 09-13 pull — the receipt auto-tap is a coin flip; a stale receipt taught a new dash the old dash's total; a fifth of declines recorded as timeouts)

**Date:** 2026-09-10 → 2026-09-12 · **Platform(s) tested:** DoorDash (app **8.96.8**, every envelope) ·
**Branch under test:** `master` at `8028691a` (post-#1094; read off the logs —
`DashBuddy 0.230.0+8028691a starting (built 2026-09-08T15:59:51.877Z)`, 62 `PipelineStats` lines all
`app=0.230.0+8028691a`). **Pre-#1100**, so the #1078/#1095 dash-end fix is NOT on this build ·
**Field conditions:** seven DoorDash dashes over two days — 09-10: 452 (08:07–09:44 $35.20), 459
(09:44–10:04 $6.20), 463 (10:04–14:21 $124.43), 476 (15:26–17:08 $40.14), 483 (17:11–17:13
`early_offline`, no deliveries); 09-11: 484 (09:58–11:01 $22.70), 488 (11:02–12:17 $32.05). Eight
H-E-B shop-and-deliver orders plus Dunkin', Bill Miller BBQ and Sprouts; 31 offers in the log window
(11 accepted / 13 declined / 7 timed out); Uber Offline throughout; no pause, no force-stop, no
unassign, no orphan, no stacked job. 682 captures (82 UNKNOWN screens, 118 UNKNOWN clicks, 3 UNKNOWN
notifications, `unknownDropped` 318 / `forwarded` 2000 ≈ 13.7 %), **71 WARN / 0 ERROR**, no
`RecognitionHealth` / `LocaleBoundary` / `TtsHealth` notice, `shareable.log` PII-free. One capability
granted (the #1093 receipt-expand, carried over from 09-08); `confirm_decline` still denied
fail-closed 31×. Pull dir `~/dashbuddy/logs/2026/09/13`.

### Bugs

1. **The #1093 receipt auto-tap failed on 5 of 11 receipts — the bind's bounds are captured while the
   sheet is still sliding up.** 11 `Performing expand_earnings` fires, 6 clean
   (`Single verified candidate … clicking it`), 5 `Could not find any live node for: expand_earnings …
   (id=null, text=null, bounds=BoundingBox(left=36, top=T, …))` → `did not fire — target failed
   resolution/verification (fail closed)` → a throttled retry 570 ms later. The five `top` values are
   **4435 / 4395 / 4263 / 2224 / 1982** on a 1080×2400 screen whose settled `This offer` row sits at
   **1774–1890**. Two envelopes of the *same* receipt 641 ms apart pin it:
   `delivery_summary_collapsed/2026-09-10_10-03-34-464` has the row at `(36,4435)-(1044,4549)`,
   `…10-03-35-105` has it at `(36,1872)-(1044,1998)`. The intervening frame
   (10:03:34.782) was classified but never admitted — identity dedup — so the bind never refreshed,
   and `SETTLE_UI` fired at 10:03:34.969, 116 ms before the settled frame arrived.
   *Hypothesis:* `GraceConfig.EXPAND_SETTLE_MS = 500` (`GraceConfig.kt:79`, consumed at
   `EffectMap.kt:307/341`) is shorter than 8.96.8's ~590–640 ms prism-sheet slide, and
   `UiInteractionHandler.findNodeByBounds` (`:372-374`) needs IoU ≥ `RELAXED_BOUNDS_IOU` 0.5
   (`:67`) against a 126 px row — at most ~63 px of tolerable drift against a fielded 110 px
   (11:05), ~350 px (12:17) and ~2 500 px (the other three). The failure is `candidates.isEmpty()`
   (`:133-139`), never a label rejection: `NONE passed verification` and the nested-abort WARN
   fired zero times, so #1093's label-hint guards were not the blocker. Would need the two
   10:03:34/35 envelopes replayed through `SessionReplay.reduceMixed` with a synthetic `SETTLE_UI`
   at +500 ms and at +800 ms to show the tap resolving only in the second. **No data was lost** —
   #1033 layer 1's 8 s window caught the dev's manual tap on all five (expansions at 2.485–3.138 s).
   - **Status:** Open (#1102 — the #1093 regression).

2. **A dash that starts while the previous dash's receipt is still on screen inherits its running
   total — session 483 reported $40.14 on zero deliveries.** Dev's own words: *"a couple of times(?)
   i think, there was a post delivery screen shown just after i started a new dash, from the last
   dash"*, and *"i think the stepper actually ignored it. at least, it didn't log anything in the
   bubble that i remember."* **Both halves check out, and they split cleanly into two layers.**
   *(1) The task/job lifecycle was INERT* — `app_events` seq 2168→2175 for session 483 holds only
   `DASH_START`, three `OFFER_RECEIVED`, one `OFFER_TIMEOUT`, two `OFFER_DECLINED`, `DASH_STOP`. No
   `DELIVERY_*`, no `DELIVERY_RECEIPT_REPRICE`, no `[Earnings]: Saved:` chat, no
   `Session Ended. Total:` bubble, zero `delivery_records` rows. *(2) The session-end payload
   LEAKED* — `session_records` 483 carries `reportedEarnings = 40.14`, exactly session 476's total.
   Trail: `DASH_START` 17:11:36.403 → `SCREEN: delivery_summary_collapsed` **17:11:36.417 (14 ms
   later)**, the envelope `delivery_summary_collapsed/2026-09-10_17-11-36-440` reading
   `Total online time 1 hr 44 min · Offers accepted 2 out of 5 · This offer $24.39` (all session
   476's figures) → the #1093 auto-tap expands the stale sheet 17:11:37.191 → `Timer Expired:
   SESSION_PAY_SETTLE` **17:11:39.669** → `DASH_STOP` seq 2175
   `{"source":"early_offline","totalEarnings":40.14,"sessionDurationMillis":null,"offersAccepted":null}`.
   *Hypothesis:* NOT the `SessionEndedFields` path — that arm (`ModeEffects.kt:82-101`) stamps
   `source = SUMMARY_SCREEN` with all three companions populated and posts a total bubble, and no
   `dash_summary` frame was classified between 17:08:04 and 09-11 11:01:12. It is the settle gate:
   `PlatformRegionStepper.sessionPayRead()` (`:1181`) counts `PostTaskFields.sessionEarnings` as a
   running-total read, `settleSessionPay` (`:1116-1152`) parks it keyed on `(flow, platform)` with
   **no session identity** in `PendingSessionPay` (`PlatformRegion.kt:506-546`), `commitSessionPay`
   (`:1203-1211`) writes it into `region.session.runningEarnings` behind only a non-null check, and
   `ModeEffects.kt:136` then stamps `prevSession.runningEarnings.takeIf { it > 0.0 }`. The
   "never invented into a later dash" guard in `commitSessionPay`'s KDoc covers a park that
   *survives* a boundary; this park was *born* 267 ms inside the new dash off a screen describing
   the old one. Distinct from #1030 (fabricated `$0.00`) and #1041 (dead `0.0` feeders) — this is a
   fabricated **non-zero carried across a boundary**. The same frame shape recurred at the 484→488
   boundary (`DASH_START` 11:02:22.985 → receipt 11:02:23.236, `Total online time 1 hr 4 min`) and
   was a near-miss: no `SESSION_PAY_SETTLE` fired (R0 left the surface inside the 3 s window) and
   488 ended on the summary screen, whose authoritative write would have overwritten a stale value
   anyway. Would need the 17:07:48→17:14:01 envelope sequence replayed through
   `SessionReplay.reduceMixed` asserting `session-483.runningEarnings == 0.0`.
   - **Status:** Open (#1103).

3. **Five of the seven `OFFER_TIMEOUT`s are declines the app failed to recognize.** All seven
   payloads carry `description: null`, so none is the #830 replace or #881 direct-offer class. Five
   reached the confirm sheet but recorded a timeout: 10:07:19, 10:08:03, 16:03:30, 17:12:01 and
   09-11 10:09:03; only 15:27:46 and 09-11 10:17:28 are genuine expiries (cards left to run 46–48 s).
   The discriminator is a race: at 10:07 the dev's confirm tap lands `10:07:18.187 UNKNOWN click —
   id=null text=null` and the `offer_popup_confirm_decline` frame is admitted **171 ms later** at
   10:07:18.358; in the working case (09-11 10:17) the sheet is admitted at 10:17:47.361 and the tap
   at 10:17:47.810 captures as `target=decline_offer ruleId=doordash.click.decline_offer` →
   `Declining…` → `Offer Declined`. The two click envelopes'
   node payloads are **byte-identical** — an id-less `android.widget.Button` at `(36,2043)-(1044,2150)`
   whose only child is `textView_prism_button_title` = `"Decline offer"`; the sole difference is
   `"screenTarget"` (`offer_popup` vs `offer_popup_confirm_decline`). Corroborating counts: 18
   `initial_decline` clicks vs only 13 `decline_offer` clicks; 31 `Denied confirm_decline` = 13
   double-denial episodes + 5 single-denial ones, exactly.
   *Hypothesis:* `doordash.click.decline_offer` is gated `screenIs: "offer_popup_confirm_decline"`
   (`matchers/rules/doordash/offer.json5`), which reads the last **admitted** screen; a confirm tap
   inside ~200 ms of the app's own `initial_decline` tap beats that frame, the click falls to
   UNKNOWN, nothing latches a decline, and the per-offer `OFFER_EXPIRY` timer later resolves it as a
   timeout. Same "faster than the animation" family as item 1, one surface over. Why 09-08 saw 0
   timeouts over 14 declines is unexplained — plausibly tap rhythm, unproven. Downstream: the Offers
   tab funnel, the acceptance rate, and #864's bubble misread all inherit the wrong outcome.
   - **Status:** Open (#1104).

4. **The `going_to_store_map` pickup contact sheet still falls to UNKNOWN on 8.96.8 — backstop as the
   primary control (#1079 recurrence).** Two `WARN/Pipeline: Capture backstop: UNKNOWN screen carried
   customer PII (textMarker=- nodeId=address_line_1) — scrubbing node from envelope` at 09-10
   08:30:36.640 and 09-11 10:27:13.379; the envelopes
   (`UNKNOWN/2026-09-10_08-30-36-685`, `UNKNOWN/2026-09-11_10-27-13-409`) carry `mx_contact_view` +
   `address_instructions_view` + `order_items_view` + `customer_contact_view`, with both address
   nodes reading `[redacted]` on disk and `user_name` text-free on these renders. Nothing leaked; the
   posture is the one #1058 argued a rule must own.
   - **Status:** Open (#1079; evidence comment posted).

5. **`earnings_deposit` persisted the DasherDirect/Crimson clause six more times** (09-10 09:44:29,
   10:04:07, 14:21:02, 17:08:02; 09-11 11:01:13, 12:17:24), each on a RECOGNIZED envelope, while the
   identical marker on the UNKNOWN path was scrubbed (`Cr7`, 09-09 17:00). Same asymmetry, sixth pull.
   - **Status:** Open (#987, dev ruling queued).

### Verification (desk)

6. **Money reconciles to the cent on all six delivering sessions** — 452 $35.20, 459 $6.20, 463
   $124.43, 476 $40.14, 484 $22.70, 488 $32.05, each exactly `Σ (realizedPay + cashTip)`. 11 drops,
   11 `DELIVERY_COMPLETED`, 11 `delivery_records`, zero `JOB_ACCEPT_MISMATCH`, zero
   `OFFER_OUTCOME_CORRECTION`, zero unattributed. Σ `realizedMiles` ≤ odometer span on every session
   (15.90/15.91, 3.03/3.03, 46.19/46.21, 11.29/11.29, 18.25/18.26, 15.04/15.05) with both legs
   non-zero on every drop — #688B holds. The two `OFFER_PAY` rows are explained and benign: seq 2060
   had an `offer_popup` inflate over the receipt at 09:27:22.416, 73 ms before the retire grace
   committed (no receipt frame at all, no `Saved:` chat); seq 2193's receipt did not render until
   11:02:23, **85 s later and inside the next dash**, and correctly emitted no re-price. Both
   estimates happened to equal the real pay exactly (no post-quote tip), so nothing is off —
   coincidence, not guarantee.
7. **#1033 layer 1 keeps working; layer 2 fired 9 times and no-op'd 9 times.** Every receipt expanded
   within 1.244–3.138 s, all inside the 8 s collapsed grace; 9 of 11 drops folded `DROP_SHARE`. The
   nine `DELIVERY_RECEIPT_REPRICE` events (seq 2071/2086/2106/2116/2126/2136/2152/2166/2210) each
   resolved `row NNNN already holds N¢ — no-op`; `receiptRepricedAt` is NULL on all 11 rows; zero
   `DELIVERY_RECEIPT_REPRICE: no delivery row` WARN; `rows_total == receipt_total` on all nine
   receipt-priced jobs. **The deliberate > 8 s late expansion and the stacked job are still not done.**
8. **#1096 measured on every receipt and benign.** Eleven first-beat UNKNOWN envelopes; the gap to
   the first recognized `delivery_summary_collapsed` is **62–613 ms** (median ≈ 424 ms), never near
   the ~2 s that would let the expand tap be scheduled off it. The `$0.00` wheel appears on three of
   them and never reaches `sessionEarnings` — the frame is UNKNOWN and therefore never parsed. Same
   beats also carried `$47009.93`, `$145.75`, `$81.23`, `$50.00`: the mid-spin class is routine.
9. **Also working:** #1063's desk half a third time (31 offers → 31 `OFFER_RECEIVED` → 31 utterances
   → 31 screenshots → 31 chat cards → 31 resolutions, zero "Replaced by new offer"/"Superseded by
   direct offer"; the three offer-shaped UNKNOWN frames carry no `display_name`, the permitted #595
   half-renders); **#995 sighted for the first time** — `pickup_receipt_scan` rendered 09-10 09:32
   and is fully masked (`Focus on [redacted:c977]` + the scanned-name node `[redacted:c977]`, same
   hex = same customer, store/order chrome intact); #1031/#1039 a second time (zero raw subpremises,
   zero `Apt [redacted:<hex>]`, `Customer Notes: [redacted]` plain, `Entry code` label only);
   #992–#994 (zero `arriving_at_title`, zero `Return …`); **#1057/#918's first field rejection** —
   `Odometer fix rejected (POOR_ACCURACY): delta=9m dt=7131ms accuracy=226 m — entering a rejection
   streak` 17:04:02, `reception recovered after 1 rejected fixes over 24 s` 17:04:27, totals 7 166
   accepted / 633 ignored / 1 rejected / +233 742 m; #991 no-regression (31/31 `speak()`, zero `-1`,
   ladder untouched); #438 B4 (10 `OfferActionReceiver` taps, every hash exact-matching its resolved
   row); #859 (31 offer screenshots, 1:1, zero `{` filenames); #588 (`0.76/min n=84`, `items:units
   0.78 n=34`, all `[doordash]`-tagged); #731 (one connect, zero disconnects); #159/#773/#1000 (five
   H-E-B stores keyed `@5601`/`@5910`/`@7330`/`@12777` + Dunkin' `@2024`, Bill Miller `@8811`,
   Sprouts `@8101`, all with real running keys, zero `D6 join miss`, all 11 accepted offers linked);
   #843's seventh mechanism corroboration; #315 H5 (11 pickup dwells 1.9–65.9 min, minutes scale);
   the #1093 `bindShortfall` census earning its keep benignly (one WARN at 12:44:58.262 on a first
   beat that rendered `This offer` without its `Expand` chevron).
10. **Not exercised:** both #1054 items for a third pull (zero `DASH_PAUSED`/`DASH_RESUMED`/
    `MODE_RESUME_COMMIT`/`SESSION_PAUSED_SAFETY`; the only restart, 09-11 22:34, replayed 4
    observations with no session and correctly logged no `Recovery re-armed`; healthy baseline —
    six summary ends at 2.501–2.506 s, two early-offline ends at 10.003/10.005 s); #1078's
    deliberate < 10 s end (tightest gap 12.4 s, session 488; 484 is the near-miss at 14.1 s with the
    retire committing 4.1 s before the summary); #1058 (`grep -c dropoff_workflow_sheet` = 0, the
    two check-1 hits are the documented text-free skeletons); #1059 a third time (zero ids tree-wide,
    and the over-match check passes a third time — 8 shop orders, 99 `pickup_shopping` + 53
    `shopping_item` frames, zero `sensitive.red_card` drops); #1034 (nothing negative); #985; #936
    (0 null distances, 0 `quality='UNKNOWN'`); #967 (no ratings browse); every Uber item; every
    dev-eyes UI item.


**Retired from the checklist at 2/2 (desk) this entry:** #1031/#1039 (two consecutive pulls with zero raw subpremises, zero hex on a subpremise, `Customer Notes: [redacted]` plain, `Entry code` label-only; the uber residual stays unexercised and is noted under #1039). #1093 is MOVED here as BROKEN (Bug 1 → #1102) per the checklist rule; a fresh validation item returns with the fix.

### Open questions

11. **The DoorDash weekly Earnings screen is in the UNKNOWN pile with the dasher's own money
    history.** `UNKNOWN/2026-09-10_14-21-24` carries `Sep 7 - Sep 13`, `$279.97`, `Active time
    9h 30m`, `Dash time 10h 39m`, `Completed deliveries 11`, `Weekly goal $279.97/$380 (73%)` and a
    per-dash list with dates and amounts. No customer PII. Two defensible postures and the dev owns
    the call: *block it* (the standing note extends the sensitive class to the dasher's own earnings
    surfaces) or *recognize it* (it is the cleanest platform-authoritative reconciliation source the
    app has seen — literally the `reportedEarnings` column with dates, which is the ask in #1035 and
    the sibling of #923). What it must not stay is what it is: unruled, on disk, under neither
    posture. Note it would have caught item 2 instantly — it lists 09-10 as three dashes, not four.
12. **Three new no-PII UNKNOWN families worth a cheap rule** — the `Pickup steps / Scan receipt /
    Verify items` wrapper pages (2 frames, 09-10 09:32), the `Can't verify items` reason sheet
    (`Bag is sealed / Items not labeled / Staff intervened`, 2 frames), and the
    `Verification still in progress … / Got it / Enter code` pair (2 frames, 09-10 15:50). The last
    one's sibling "Enter code" screen deserves a Pledge look before a rule lands.
13. **Two duplicate evidence screenshots landed in the wrong session** as a side effect of item 2 —
    `2026-09-10 1711 Delivery - 24.39.png` and `…DeliveryBreakdown - 24.39.png`, second copies of
    session 476's receipt written 3.5 minutes later under session 483 (the rule's 60 s `throttleMs`
    had lapsed). Cosmetic, but it is the one user-visible trace the stale-frame class leaves.
