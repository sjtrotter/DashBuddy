## 2026-06-25 → 06-30 — DoorDash field week (desk synthesis of five dashes)

- **Date:** 2026-06-25, 06-26, 06-28, 06-29, 06-30 (analyzed 2026-07-01)
- **Platform(s) tested:** DoorDash
- **Branch under test:** `master` at `44e0d0e2` (post-#584 — includes #577 quick-decline
  auto-confirm, #578/#583 rich offer heads-up with in-card Accept/Decline, #517/#518/#498
  ghost/phantom fixes).
- **Field conditions:** Five dashes, San Antonio, 44 offers total (per the db: 19 accepted /
  24 declined / 1 timeout as *recorded* — see Bugs #1/#2 for why recorded ≠ real), heavy H-E-B
  shop-and-deliver mix plus several 2-store stacks, one alcohol drop, one at-store unassignment
  and one order swap. Desk synthesis of the pulled logs (~208k lines), `dashbuddy-v2.db`
  (app_events seq 1–249), and the capture corpus — not in-field narration. **Hypotheses, not
  concluded fixes.** Headline receipts (seq 226/227, 141/142, 241/242, DASH_STOP arithmetic,
  the H1 click-by-click log lines, the PII captures) were independently re-verified against the
  raw db/logs after the multi-agent analysis. Zero ERROR lines and zero pipeline restarts all
  week — the pipeline itself was healthy.

### Verification TODOs (checklist confirmations this week)

1. **✅ #583 / PR #584 — in-card heads-up Accept/Decline buttons — VALIDATED (well past 2/2).**
   `OfferActionReceiver` fired on all five days (≥16 clean end-to-end accepts/declines), each
   resolving to the matching db outcome within seconds. Frame-continuity around the taps shows
   they came **from the floating heads-up, not a shade-pull** — the field gate ("if they only
   work after a shade-pull, revert to the action row") **passes affirmatively**. The one failure
   all week (Bug #5) was the shade/lock case — the inverse, handled fail-closed. **Gauge-ring
   visual is NOT verifiable from desk data** — kept on the checklist as a visual-only item.
2. **✅ #578 / PR #581 — rich offer heads-up card — mechanically confirmed (≥2/2).** The card
   posted with live PendingIntents on every offer (every receiver fire proves an actionable
   rendered card); zero RemoteViews/inflate/fallback errors in ~208k lines. Verdict banner /
   ticking countdown / badge **visuals** stay on the checklist for developer eyes.
3. **✅ #577 quick-decline — re-confirmed mechanically and RETIRED from the checklist** (was
   validated 2/2 on 06-24): 24/24 confirm sheets auto-clicked, latency consistently ~0.52–0.66 s
   from sheet recognition (~1.2–1.6 s tap-to-confirm end-to-end — the 06-24 "feels slow" now has
   numbers), throttle + fail-closed refusals worked. **New posture caution:** it is a causal
   ingredient in Bug #1 (it forecloses the change-of-mind window the confirm sheet provides),
   and every fire rides the Bug #6 tie-break path.
4. **✅ #457 heads-up notification path — RETIRED** (validated 06-24; this week adds ≥16 more
   clean receiver-path confirmations over five days).
5. **✅ #554 ShadowProjector — 2/2, RETIRED.** 06-29/06-30 chains accurate vs the db; it also
   faithfully *exposed* the merged-job defect (Bug #3): `job …-55 store-chain (2): [Pizza Hut] …
   [H-E-B] offer=— dropoff=— payout=— custs=[]`.
6. **#557 multi-store dropoff store — 1/2.** 06-29 Sally Beauty + Panda Express stack: all four
   tasks correctly store-attributed with distinct customer hashes. Remaining gap is fold-in/leg-2
   dropoffs (`storeName=null` → "the customer") — Bug #10b, #526-widened scope, not a #557
   regression.
7. **#556 shop time model — 1/2.** 06-30: a 41-item H-E-B priced at $9.94/hr (a sane decline);
   shop offers all week in a believable $14–27/hr band; `ShopRate` learning lines present (11×).
   Caveat: a 44-item shop estimated ~83 min vs ~2.5 h actual — the seed is still optimistic on
   giant shops.
8. **✅ #517/#518/#498 fixes HELD all week** — zero duplicate-hash re-receives, zero $0/phantom
   completions, zero cross-job completion leaks. (Bug #2 is a *new* ghost variant that slips the
   #498 pay gate — not a regression.)
9. **Alcohol flow (#463 reversal, PR #485) — 1 clean sighting; the sibling #462/#460 dropoff
   item is found BROKEN-IN-PART and moves here.** 06-30 CVS alcohol drop: verify-checklist +
   ID-check screens recognized, the scanner capture contains instruction text only, events
   hashed. But the item's own broken-criterion — "raw recipient name/address appears anywhere" —
   is tripped by Bug #7 (raw PII in recognized/UNKNOWN capture envelopes), so the item leaves
   the checklist as partially broken rather than confirmed.

### Bugs

1. **Decline recorded as OFFER_ACCEPTED — the remembered incident, pinned (06-30 16:59, Burger
   King $6.25, seq 226/227).** Dasher tapped Decline (16:59:14.872,
   `app_log_rotated_20260630_174600.log:6295`); #577 auto-confirmed the confirm sheet correctly
   at 16:59:16.128–16.378 (`:6323–:6328` — the click echo classified as the **confirm sheet's**
   `decline_offer` while the sheet was still on screen, so the "late auto-click retargeted the
   offer screen's Decline" theory is **refuted**); the offer card resurfaced at 16:59:16.969
   (consistent with a "Review offer" tap — no click rule exists for that control) and the dasher
   tapped Accept at 16:59:17.582 — 1.2 s **after** the decline had already gone out. DoorDash
   kept the decline: no job/pickup ever formed (the only accept all week with no immediate
   PICKUP_NAV_STARTED; next event is the next offer), and DASH_STOP seq 249 records
   `offersAccepted=3`, `$45.75 = 9.25 + 9.75 + 26.75` — the $6.25 absent. The app committed
   OFFER_ACCEPTED by last-click-wins at card-vanish (16:59:24.760); the chat surface showed
   "Offer Declined" then "Offer Accepted" 1.2 s apart (Bug #9).
   - **Likely cause (hypothesis):** the pending offer resolves from the last click before the
     card disappears, with no reconciliation against job formation — the machine already had the
     contradicting signal (no task minted, idle screen at 16:59:32). One possibility is a
     job-materialization grace check that flags/downgrades an accept with no ensuing pickup;
     another is latching "decline submitted" once auto-confirm fires. Separately, #577's ~0.55 s
     speed forecloses the change-of-mind window the confirm sheet exists to provide — a product
     posture question beyond the state bug.
   - **Status:** Open — filed **#594** (2026-07-02 triage).
2. **Ghost offer re-mint at accept — the remembered ghost, pinned twice (06-28 seq 141/142,
   06-30 seq 241/242).** After a real accept of a SHOP offer, the post-accept transitional
   `offer_popup` frame (order rows/store gone; one frame carries a raw UUID in the store slot;
   pay + distance persist) re-parses as a **new** offer (`orders=[]`, `itemCount=0`, "Unknown
   Store", **different offerHash**) which **replaces** the just-accepted pending offer — the
   real OFFER_ACCEPTED rows literally carry `description='Replaced by new offer'`. The eval
   loopback then runs on the ghost (0 items zeroes the #556 shop time → inflated $/hr), TTS
   speaks "Accept. Unknown Store. 40/56 dollars an hour net", a bogus `[Good Offer]` chat card
   posts, and 1–2 s later the ghost resolves as a user-visible "Offer Timed Out!" — an **orphan
   OFFER_TIMEOUT with no OFFER_RECEIVED row** (verified: both TIMEOUTs match zero received
   hashes; this is the +1 over-resolution on both days). Ghost frames on disk:
   `offer_popup/2026-06-28_17-16-35-464__…__08da30.json`,
   `offer_popup/2026-06-30_17-20-17-111__…__cf8cc3.json`.
   - **Likely cause (hypothesis):** offerHash is computed over parsed fields that go degenerate
     on the teardown frame, minting a fresh identity the #498 guard can't catch (that guard
     gates on missing pay; this frame *has* pay). One possibility: reject/absorb an offer parse
     with empty orders / UUID-shaped store text when a same-pay/same-distance offer was decided
     <2 s earlier — needs a settle/validity design call. (This is the post-accept trigger
     predicted in the 06-15 checklist note under the old #498 item.)
   - **Status:** Open — filed **#595**.
3. **Receipt-skipped completions: 6 of 21 confirmed dropoffs never got DELIVERY_COMPLETED
   (verified 21 vs 15 week-wide), and the never-closed job absorbs later offers.** 06-29 job-42
   (Sally Beauty + Panda Express, $15.40): both drops DELIVERY_CONFIRMED, **zero** completions.
   06-29 Pizza Hut: `dropoff_completed_confirm` → straight to `waiting_for_offer`, no receipt →
   no completion (~$12.00 actual pay never attributed; internal 29.49 vs summary 41.49) — and
   the still-open job then **absorbed the next accepted H-E-B $13.50 offer** as tasks 58/59
   under the Pizza Hut jobId. 06-30 job-61 spans **three independently accepted offers** (BJ's
   $9.25, CVS $9.75, H-E-B $26.75) with 3 confirms / 1 completion — $19.00 of $45.75
   unattributed per-delivery. Session totals stayed correct everywhere (DASH_STOP is the outer
   truth anchor); the damage is per-job/per-delivery attribution.
   - **Likely cause (hypothesis):** DELIVERY_COMPLETED mints only from the delivery-receipt
     authoritative window (#431); when DoorDash chains the next offer over the receipt or skips
     it entirely, no completion fires and the job never closes, so subsequent accepts look like
     add-ons. One direction might be a job close-out on `waiting_for_offer` / fresh-store
     accept. Beyond the known #528 scope (zero-completion jobs and cross-offer absorption are
     new); touches #527 job-lifetime.
   - **Status:** Open — filed **#596** (blocks #528).
4. **Order unassignment/swap is unmodeled — and can confirm the wrong pickup.** 06-26 Petsmart:
   the order was unassigned-with-no-pay at the store (resolution-sheet capture shows "Unassign
   with no pay / Completion Rate will drop to 97%"), but at the dropoff transition the stepper
   minted PICKUP_CONFIRMED for **Petsmart — the abandoned order** (seq 85) while Panera
   (actually picked up and delivered) never confirmed; the job closed at $8.90 vs $21.07
   offered, the −$12.17 delta uncaptured. 06-28: an $18.50 H-E-B order broke at the store →
   ~30-min support chat → "Missed Delivery" notification → a $22.76 replacement offer folded
   into the same job-38, re-emitting duplicate PICKUP_NAV/ARRIVED (seq 143/144); the $18.50
   accept dangles forever and **DoorDash's own summary says 2/4 accepted vs DashBuddy's 3**
   (DASH_STOP seq 150: $35.80 = 13.04 + 22.76 exactly — the $18.50 absent).
   - **Likely cause (hypothesis):** the event vocabulary has no unassign/cancel/supersede
     terminal for an accepted order, and the issue/resolution screens are recognize-only with
     no state transition; the pickup-confirm inference fires for the *active* task at phase-flip
     regardless of which order was physically abandoned.
   - **Status:** Open — routed to existing **#301** (scope widened with this evidence).
5. **#583 in-card Accept failed once, fail-closed ("No live windows") — 06-25 16:44:38, $13.50
   H-E-B.** The only receiver-path failure of the week (~1/18). The receiver fired
   (`app_log_rotated_20260625_173549.log:7881–7887`) but window enumeration found no DoorDash
   window; a ~5.5 s accessibility-frame gap brackets the tap — consistent with a
   shade-pull/lock-screen press (the SystemUI-takeover class #457 was built around). Dasher
   recovered manually ~25 s later; recorded outcome correct, but the button was visibly dead —
   a near-miss on losing the offer to timeout.
   - **Hypothesis:** one possibility is a short bounded re-resolve retry (~1–2 s) after the
     shade collapses; the fail-closed denial itself behaved correctly.
   - **Status:** Open — filed **#602**.
6. **Verified-click bounds pinning never matches — every automated click rides the tie-break.**
   ~40+ WARNs `No exact bounds match among N verified candidates — clicking first` across
   confirm_decline (21–24×), decline_offer, expand_earnings; bounds pinning matched **zero**
   times all week. It picked the right node every time this week, 5 stale re-fires were
   correctly refused fail-closed ("NONE passed label verification … refusing to click"), and
   there was one throttle escape (06-26 18:32:26 → 18:32:27.311 — 1.29 s apart, outside the
   1000 ms window). But "first of N same-labeled candidates" is #425's exact threat model, one
   DoorDash layout change from a wrong click.
   - **Hypothesis:** recognition-time bounds don't survive to click-time re-resolution
     (drift/coordinate space), so the exact-bounds fast path is dead code in practice; the
     confirm sheet's Button also has no own text (label lives in a child TextView), making
     candidate enumeration ambiguous. Also a frequent-benign WARN drowning the channel
     (#551/Principle 7 concern).
   - **Status:** Open — filed **#600**.
7. **Raw customer PII persists in capture envelopes (the event/log path is clean).** Recognized
   dropoff captures (`dropoff_handoff`, `dropoff_navigation`, `dropoff_pre_arrival`, 06-29/06-30)
   hold plaintext "Deliver to ‹customer name›" + full street address + one gate code; a pickup
   issue screen holds "For ‹first name + last initial› • Petsmart"; 23 `pickup_arrival` captures
   + UNKNOWN captures hold "Order for ‹name›". The `SensitiveTextMarkers` UNKNOWN backstop is
   marker-based and cannot catch bare names. **Contrast (working as pledged):** DELIVERY_*
   payloads carry sha256 hashes only, and full-week INFO+ scans found **zero** raw customer
   strings (personas read "H-E-B's customer"/"the customer"). Debug-only capture binding (#346)
   limits exposure to the dev device, but the pledge is hashed-at-edge-before-persistence —
   today it is hashed-in-parse-while-raw-on-disk.
   - **Status:** Open — filed **#598**. (Trips the #462/#460 checklist item's broken-criterion — moved here.)
8. **Dasher-banking pledge hole: `doordash.notification.crimson_balance` recognizes and stores
   the Crimson/DasherDirect Savings Jar balance raw** — "…balance is now $‹amount›", 9 capture
   files spanning 06-03→06-28. The pledge blocks the dasher's banking/DasherDirect balances at
   the matcher layer (never parsed or stored); a recognize+capture rule is the opposite.
   `earnings_deposit` is the borderline sibling (stores transfer notifications raw).
   - **Hypothesis:** likely added to name/suppress the notification as noise without marking it
     sensitive; the notification capture path stores whatever a rule recognizes.
   - **Status:** Open — filed **#599**.
9. **Chat/bubble outcome cards are click-driven, not committed-outcome-driven (SSOT
   divergence on the trust surface).** "Offer Declined" → "Offer Accepted" 1.2 s apart (Bug #1);
   "Offer Accepted" → "Offer Timed Out!" 1–2 s apart (Bug #2, both instances). The bubble feed
   contradicted itself in every incident window this week.
   - **Hypothesis:** chat effects fire eagerly on click/resolution observations instead of
     deriving from the single committed outcome event (Principle 5).
   - **Status:** Open — filed **#601**.
10. **Smaller items:** (a) stacked 2-pickup jobs: only the **last** store gets PICKUP_CONFIRMED
    (3/3 stacks; N−1 stranded at "arrived" — fully explains 06-26's 5-arrived/3-confirmed
    aggregate); (b) `dropoff_handoff` matches en-route "Deliver to …" frames → false-early
    arrivals (`arrivedAt == phaseStartedAt` on 2 of 3 drops 06-30; the 06-21 Walgreens class)
    and leg-2 drops miss DELIVERY_NAV_STARTED + store attribution (`storeName=null` → "the
    customer"); (c) duplicate DASH_PAUSED via pause→grace-resume→pause (06-28 15:04:32/15:04:38,
    two user-visible "Dash Paused!" cards); (d) **notification effect idempotency keys are
    global, not per-notification** — `effect:doordash.notification.new_order:log` fired once
    on 06-25 16:31 and deduped every subsequent new-order notification all week; (e) all 46
    offer screenshots saved as the literal filename `Offer - {storeName}.png` (template never
    interpolates; same class as the #433 dedupeKey lint, for the screenshot effect) and the
    DashSummary screenshot double-fires at session end; (f) tip parse label junk
    (`customerTips[{type:'799'|'618'}]` — stray UI tokens; amounts correct); (g) the UNKNOWN
    capture cap (200) was hit 25 min into 06-28, suppressing triage captures for ~4 h including
    the entire support-chat flow.
    - **Status:** Open — filed/routed 2026-07-02: (a) → existing **#526** (comment); (b) → **#603**;
      (c) → **#605**; (d) → **#604**; (e) → **#606**; (f) → **#607**; (g) → **#597** (with the
      click-capture starvation below).

### Field UX context

- DoorDash frequently **skips the per-delivery receipt** — chaining the next offer over it or
  returning straight to `waiting_for_offer` — so completion minting can't rely on that surface
  alone (Bug #3).
- The offer card has a **post-accept transitional render** (order rows collapse; one frame shows
  a raw UUID in the store slot; a "Pro / High paying offer" banner variant) that still carries
  pay + distance and the Accept/Decline chrome (Bug #2's trigger).
- The confirm-decline sheet offers **"Review offer" / "View offer details"** back to the offer
  card — with auto-confirm ON that path is a trap: the decline is already committed server-side
  by the time the card returns (Bug #1).
- Store issue → support chat → **"Missed Delivery" → replacement offer** is a real mid-job flow
  (06-28: ~30 minutes in-chat, then a smaller replacement of the same shop).

### Open questions / investigations

1. Bug #1: no click rule exists for "Review offer" — that step is inferred from the screen
   return + the developer's recollection. Would a click rule/capture for that control settle
   the sequence beyond doubt?
2. Bug #2: is the degraded/UUID offer frame **only** an accept-teardown render, or also an
   arrival-time pre-render? (UNKNOWN captures suggest arrival-time UUID frames on 06-25 that
   happened not to match.) Determines where a validity/settle gate would belong and whether it
   risks rejecting real offers.
3. Bug #3: Pizza Hut's ~$12.00 is inferred by subtraction (41.49 − 29.49) — no receipt ever
   rendered. Is any DoorDash surface (earnings tab) worth capturing to attribute it?
4. Bug #5: the shade/lock tap is inferred from a 5.5 s frame gap — is there a cheap
   screen-state/SystemUI signal worth logging at fire time to distinguish shade taps?
5. 06-28 DoorDash summary "2/4 accepted": does the platform count the swapped order as a
   decline, or exclude it? Affects what an unassign/supersede terminal should reconcile against.
6. Click envelopes were not persisted this week (`captured=false` on click lines) — intended?
   It cost forensic corroboration for both headline incidents. **RESOLVED 07-02: not intended**
   (dev: clicks should record on debug builds). Root cause found at desk: `DiskCaptureBus`
   in-memory contentHash dedup + the a11y process living for days → repeat-tap clicks dedupe
   forever (37→17→1→2→0 captures across the week); same family as `FrameGate`'s per-process
   UNKNOWN cap (200). Filed **#597**.
7. #583 gauge ring and #578 countdown/badge **visuals**: desk data can't see them — developer
   eyes or screenshots next dash (the checklist items are now visual-only).

### Meta / architecture

1. The week's two headline incidents share a root shape: **the machine trusts a single surface
   observation (a click; a re-parsed frame) over corroborating lifecycle evidence it already
   has** (no job formed after an "accept"; a same-pay "new offer" seconds after one resolved).
   A reconciliation-window pattern (accept ⇒ expect job formation; new offer ⇒ suspect
   teardown) may be a general direction — dev's call.
2. Session-level money reconciled to the cent on all five days; every gap was per-job/per-task
   attribution, not totals — the DASH_STOP summary path is doing its job as the outer truth
   anchor.
