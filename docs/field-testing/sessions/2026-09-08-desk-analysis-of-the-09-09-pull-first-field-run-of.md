## 2026-09-08 (desk analysis of the 09-09 pull — first field run of #1093, which works; a dash-end race silently ate a $21 drop)

**Date:** 2026-09-08 · **Platform(s) tested:** DoorDash (app **8.96.8** — moved from 8.95.6; every one of the
332 envelopes) · **Branch under test:** `master` at `8028691a` (post-#1094 merge; read off the logs —
`DashBuddy 0.230.0+8028691a starting`, built 2026-09-08T15:59:51Z, running from 11:00:25 with `restarts=0`;
the two `3ef5eff9` startups at the head of the first rotated log are 09-07 evening) · **Field conditions:**
three DoorDash dashes (15:44–17:43 $50.57 · 17:52–18:55 $23.00 · 19:06–19:13 `early_offline`), all three
H-E-B **shop-and-deliver** orders, the first dash entirely out-of-zone "Dash Along the Way" so DoorDash
rendered **no** delivery receipt for either of its drops; 17 offers / 3 accepted / 14 declined; Uber Offline
throughout; no pause, no force-stop, no unassign, no orphan. 332 captures (36 UNKNOWN screens, 25 UNKNOWN
clicks, 2 UNKNOWN notifications, 13.0 % UNKNOWN ratio), **42 WARN / 0 ERROR**, no `RecognitionHealth` /
`LocaleBoundary` / `TtsHealth` notice, `shareable.log` PII-free. The dev granted **one** capability at
11:05 — the #1093 receipt-expand — and nothing else. Pull dir `~/dashbuddy/logs/2026/09/09`; device purged
after the pull.

### Bugs

1. **Session −440 silently lost a delivered $21.00 drop; the dash-summary grace evicted the task retire —
   #1078 recurrence, mechanism now pinned.** Job −444 was accepted 16:52:01 ($21.00, offer `bf0fb9a1`),
   shopped (11 items), `PICKUP_CONFIRMED` 17:34:29, `DELIVERY_ARRIVED` 17:43:35, drop-off photo taken
   17:43:38 — and then `DASH_STOP` (seq 2007) and `DELIVERY_CONFIRMED` (seq 2008, `completedAt: null`) both
   landed at **17:43:54.709** with **no `DELIVERY_COMPLETED` and no `delivery_records` row**. The session
   reported $50.57 and recorded $29.57; the $21.00 gap is that offer's `payAmount` to the cent, and
   `offer_records` seq 2001 has `linkedJobId` NULL. The pickup half folded fine. Nothing WARNed.
   *Hypothesis:* the frame trail is `dropoff_photo` (`task:dropoff:arrived`) → **`dash_along_the_way`
   17:43:43.859** (`idle`), which arms `TASK_RETIRE` at +10 s = 17:43:53.859 (`TaskLifecycle.kt:446-467`) →
   `end_dash_confirm` (lifecycle-neutral) → **`dash_summary` 17:43:52.245** (`session:ended`), whose `else`
   arm in `PlatformRegionStepper.kt:653-684` builds a **fresh `SESSION_END` pending that REPLACES the live
   retire** 1.614 s before it would have committed (the `Mode.Offline` early-return at `:687-691` drops a
   `TASK_RETIRE` for the same reason) → `GRACE_COMMIT` 17:43:54.707 → `endSession` force-stamps
   `completedAt`, and `mintQualified` (`DeliveryCompletionEffects.kt:489-491`, judged against the PREV
   region) correctly refuses it because `retirePending` is now false (`:281`) — the T3 false-completion
   guard doing its job on evidence the machine had already thrown away. It is a **race, not a
   determinism**: the same shape two hours earlier (delivery 1, `dash_along_the_way` 16:51:06.625 →
   `GRACE_COMMIT` 16:51:16.620) completed normally because nothing armed a session end inside the retire
   window; the dev ended the dash 8.4 s after the drop instead of waiting. Would need the 17:43:35→17:43:56
   sequence replayed through `SessionReplay.reduceMixed` with a synthetic `GRACE_COMMIT` to confirm, and a
   second run with the summary frame moved past 17:43:53.859 to show the completion returning.
   - **Status:** Open (#1078 — second sighting, the cleaner repro; evidence comment posted).

2. **The #810 B1 accept-mismatch tripwire cannot see item 1.** `JobCloseEffects.kt:38-45` returns empty when
   `next.session == null` ("endSession is out of scope (v1)"), and job −444 closed through exactly that
   teardown — so a job with 1 accepted pay-bearing offer and 0 accounted drops produced no
   `JOB_ACCEPT_MISMATCH` and no WARN, and `session_records.deliveries` still reads 1. *Hypothesis:* the v1
   exclusion was aimed at the offline-mid-job / new-session mis-attribution case, which the session-identity
   test on the same line already handles; a dash-end teardown that strands an accepted offer is a different
   shape — the one the tripwire's own KDoc describes.
   - **Status:** Open (#1095).

3. **The "Delivery for" pickup contact sheet still falls to UNKNOWN on 8.96.8 — backstop as primary control
   (#1079 recurrence).** `UNKNOWN/2026-09-08_17-14-35-961__…__8d8cb4.json` (`customer_contact_view/user_name`
   under `going_to_store_map` + `Arrived at store`) was written only because the #910 `user_name` id backstop
   scrubbed the node first (`WARN/Pipeline: Capture backstop: UNKNOWN screen carried customer PII
   (nodeId=user_name)`). Nothing leaked — the name reads `[redacted]` and the three address nodes were
   text-free on this collapsed render — but this is the posture #1058 argued a rule must own.
   - **Status:** Open (#1079; evidence comment posted).

4. **New 8.96.8 receipt first beat lands in UNKNOWN.** `UNKNOWN/2026-09-08_18-53-00-789` reads `This dash so
   far · $ 0 . 0 0 · You got to 25% of your goal · Total online time 1 hr 0 min · Offers accepted 1 out of 4 ·
   Send thanks to the customer for the tip! · Send · Continue dashing` — the post-delivery sheet 656 ms before
   the recognized `delivery_summary_collapsed` frame, with no `This offer` row yet (so
   `allTextContainsAny: ['this offer', 'delivery complete']` cannot match) and new `Send thanks…` chrome absent
   from the corpus. Benign today; it is the same two-beat inflation shape as #1063's offer card.
   - **Status:** Open (#1096).

5. **`earnings_deposit` pushes stored "DoorDash Crimson account" twice more — still #987.** Both 09-08
   deposits persisted the clause on a RECOGNIZED envelope while, 12 h later, the identical marker on an
   UNKNOWN notification was scrubbed (`… hit sensitive marker id 'Cr7'`, 09-09 06:37:18) — the asymmetry in
   one pull.
   - **Status:** Open (#987, dev ruling queued; evidence comment posted).

### Verification (desk)

6. **#1093 CONFIRMED on its first field run, end to end.** The consent prompt appeared and the dev granted
   exactly one key — `f5e440da78d318ee0c579e216a9e6be1…`, which recomputes from the generated ruleset as
   `doordash.screen.delivery_summary_collapsed / expand_earnings / expandButton`. On the dash's one receipt:
   `SETTLE_UI` 18:53:02.074 → `INFO/Effects: Performing expand_earnings` → `attempting verified click` →
   `DEBUG/Effects: Single verified candidate … clicking it` (18:53:02.439) → `delivery_summary_expanded`
   18:53:02.945. **Collapsed→expanded 1.500 s** — the auto-tap shape, not the 2.5–3 s of the 09-05/06/07
   manual taps. No `Could not find any live node` / `NONE passed label verification` / aborted tie, and
   `bindShortfall{}` never named `delivery_summary_collapsed.expandButton` (only the expected
   `offer_popup#declineButton`). The drop folded `DROP_SHARE` with tip $10.00 / base $13.00 / $23.00.
   Only ONE receipt occurred — the other two drops were out-of-zone with no receipt at all.

7. **#1029's mid-spin guard fired in the wild on the receipt's own wheel.** The first collapsed frame
   (18:53:01.445) rendered `$ 2 9 3 . 0 9 0` — out of `CurrencyShape`, rejected — and the re-render 121 ms
   later read `$23.00` and won (`sessionEarningsAtCompletion: 23.0`), which is the `PostTaskFields.dedupeHash`-
   folds-`sessionEarnings` admission path working. Two more mid-spin reads on `idle_map`
   (`$ 8 9 9 1 . 3 6 0`, `$ 0 1 0 1 9 4 . 6 9 0`) confirm the class is routine on 8.96.8. The settle TIMER
   never armed (zero `SESSION_PAY_SETTLE`): session 440 showed no `waiting_for_offer` frame at all and
   session 447's three reads agreed with the total already in state.

8. **Also working:** #1033 layer 1 (2nd — **RETIRED at 2/2**; the "Saved: $23.00" bubble still fires on the
   receipt frame at 18:53:01.466, 21 ms after it appears) with layer 2 firing and correctly no-op'ing
   (`row 2024 already holds 2300¢`) — the deliberate >8 s late expansion was NOT done, layer 2 stays 1/2;
   #1063 desk half (17 offers → 17 `OFFER_RECEIVED` → 17 utterances → 17 screenshots → 17 resolutions, zero
   timeouts, zero "Replaced by new offer"; the two offer-shaped UNKNOWN frames are store-leg-less #595
   half-renders — **2/2 desk, dev-eyes half still open**); #1030 (session 451 reported nothing rather than a
   fake $0.00); #1057/#918 (1 754 accepted / 146 ignored / **0 rejected**, +56 682 m against 36.94 mi of
   span); #967 (`ratings.capturedAt` advanced to 18:57:32 on an **Offline** browse — **2/2 desk**, dev-eyes
   half still open); #438 B4 (10 `OfferActionReceiver` taps, all 10 hashes exact-matching their
   `OFFER_DECLINED` row); #859 (17 offer screenshots, one per presentation, every filename carrying a real
   figure); #588 (`0.76/min n=73→75`, `items:units 0.79 n=28→30`, all `[doordash]`-tagged); #731 (one
   connect per process, zero disconnects); #159/#773/#1000 (three H-E-B stores keyed `@5601`/`@5910`/`@7330`
   with real running keys, zero `D6 join miss`); #688B (Σ `realizedMiles` ≤ span on both sessions, both
   legs non-zero); #843 (`none granted — awaiting consent` on both starts, then one explicit grant — no
   auto-grant); the #1031/#1039/#992–#995 redact greps (zero raw names, streets, subpremises, codes or notes
   anywhere; every subpremise, ZIP, note and the one `Entry code` block on the six `dropoff_pre_arrival`
   envelopes reads PLAIN `[redacted]`; the only raw addresses are the two H-E-B **merchant** lines on
   `pickup_navigation`, raw by design per #886); money reconciles to the cent on sessions 447 and 451.

9. **Not exercised:** both #1054 items (no pause, no force-stop, no recovery with a live grace — the only two
   restores were `no tail` with no session; the two summary-ended `DASH_STOP`s show `occurredAt − endedAt` =
   2.508 s / 2.575 s, the ordinary 2.5 s window, healthy); #1058 (`grep -c dropoff_workflow_sheet` = 0;
   check 1's three hits are the documented text-free-skeleton false positives, second pull running); #1059
   (no Persona / Red Card wallet / passport — though the OVER-match check passes again: two `Red Card` shop
   offers and three shop pickups produced zero `sensitive.red_card` drops); #1034 (nothing went negative);
   #991 (17/17 utterances, ladder untouched); #985; #736/#752/#749/#660p2/#501/#526; every Uber item; every
   dev-eyes UI item.

### Open questions

10. **Two `CONFIRM_DECLINE` intents per decline.** 28 `Denied confirm_decline … (fail closed)` over 14
    declines — exactly 2 each, because `EffectMap.diffConfirmDeclineAction` schedules one `SETTLE_UI` per
    admitted confirm frame with no dedupe and the sheet is admitted twice (before and after the dev's own
    tap). The denial itself is right (the capability was never granted, and `SideEffectEngine.kt:362` checks
    it before the quick-declines setting at `:375`). With the capability granted the second tap would land
    on a dismissed dialog and abort at verification — noise rather than harm (28 of the dash's 42 WARNs).
    - **Status:** Open (#1097).

11. **DoorDash 8.96.8.** The #1029 re-anchors, the #1093 expand bind and every redact held across the bump.
    The new shapes are items 3 and 4 above; the 09-09 corpus intake captures the slice.
