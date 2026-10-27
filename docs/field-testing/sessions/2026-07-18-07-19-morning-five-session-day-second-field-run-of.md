## 2026-07-18 (+ 07-19 morning) — five-session day; second field run of the #767–#791 build (desk-analyzed 2026-07-19)

- **Date:** 2026-07-18 (four sessions) + 2026-07-19 morning (one session)
- **Platform(s) tested:** DoorDash. Uber was opened for ~1 minute on 07-19 (~07:15, home dashboard + go-online screens captured) but **never driven** — zero Uber events/sessions; the standing "drive Uber" want remains unmet.
- **Branch under test:** the 2026-07-17 install (master @ `064b3fd4` era, #767–#791). The 07-18 merge wave (#793/#797/#799/#800/#802/#805) is NOT on this build.
- **Field conditions:** 5 sessions, $133.57 total ($113.07 on 07-18 across 4 sessions + $20.50 on 07-19), 7 deliveries — all single-drop single-store jobs (no stacks). 25 offers → 9 accepted / 14 declined / 2 timeout. Two pickup-phase unassigns (both H-E-B). One session ended early_offline with $0.

### Verification results (desk pass, receipts in the 2026-07-19 analysis)

1. **Money path: exact to the cent on all four earning sessions** ($26.45 / $55.12 / $31.50 / $20.50 = reported exactly). Zero orphans, zero over-attribution, all DROP_SHARE. Frozen-econ invariant exact (fuel+nonfuel == cpm, diff 0.0) on all 7 rows. Event stream: 0 ERROR, 0 restarts, 16 benign WARNs; projector v7 caught up (watermark 398).
2. **VALIDATED 2/2 → retired from the checklist this entry:**
   - **#788** automation taps — 30 clean `Single verified candidate` fires (1 accept, 14 confirm_decline, 6 decline, 9 expand), zero aborts, second consecutive clean dash.
   - **#736** pickup-phase unassign — TWO clean unassigns in one pull (07-18 seq359 + 07-19 seq376): one `TASK_UNASSIGNED` each, zero fabricated confirms/completions, event arithmetic exact (9 accepts − 2 unassigns = 7 completed). The dropoff-phase retro-mark (#752) remains unexercised.
   - **#435** pre-map skip — negative half: `mappingFailures=0` across 23 stats lines, 1500 frames forwarded, all deliveries recognized. (Positive half is device-logcat-only by design — retired on the negative evidence.)
   - **#159** basic store keying — all deliveries carry real running keys; remaining scope narrowed to the multi-store-from-one-receipt case (still unfielded).
3. **#773 second field receipt** (already retired 07-17): SIX distinct H-E-Bs now keyed by street number (`@12125/@7330/@5910/@9255/@5601/@12777`) — the 07-13 "4 H-E-Bs conflate to one chain row" world is fully gone.
4. **#731: fourth consecutive clean night** (connects=1/disconnects=0) — environmental root-cause stands; stays parked.
5. **Not exercised:** #630 (stays 1/2), #733 (1/2), #752, #749, #691, #660p2, #778 (shopping stayed on), #722, and every Uber item (#762 D2 skeleton, coarse-close WATCH, #785/#786 captures).

### Bugs

1. **[HIGH, privacy — second field receipt for #794, fix already merged as PR #802]** The DasherDirect "Transfer out" screen leaked again on this pre-fix build (2 UNKNOWN captures, balance amount masked here). Both frames verified to carry exactly PR #802's anchor pair, so the merged fix covers the shape. Files purged from device + pull. The #794 checklist item's confirmations must come from a post-#802 install.
2. **[MED-HIGH, privacy — filed #806]** Five unrecognized DoorDash **task-detail** screens ("Deliver to"/"Pickup for" detail views) leaked raw customer name, full address, and a gate code to UNKNOWN captures. Structural gap: customer hashing requires recognition, and the `CustomerTextMarkers` backstop covers recognized frames + notifications, not UNKNOWN screens. Files purged; surface is trivially re-capturable when the rule is built.
3. **[LOW, recognition — added to #796]** New "Dasher Rewards / Quality Rate" program family (8+ UNKNOWN frames) — recognize-only candidate.

### Open questions / investigations

1. Whether the #802 sensitive block should be joined by an UNKNOWN-screen `CustomerTextMarkers` scrub (the #806 fix-direction question — probably both, see the issue).
