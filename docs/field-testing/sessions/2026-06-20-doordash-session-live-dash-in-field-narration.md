## 2026-06-20 — DoorDash session (live dash, in-field narration)

- **Platform tested:** DoorDash
- **Branch under test:** `master` (build inferred — developer to correct if running a feature branch).
- **Field conditions:** Live Saturday dash. Single ACV (alcohol) Shop-&-Deliver offer from **H-E-B**
  (grocery). Observation narrated from the bubble HUD while the offer/pickup card was on screen; one
  screenshot captured. Mid-dash, a **real phone power-off at the H-E-B checkout lane (~17:01)** gave a
  live **crash-recovery** test (item #2). **Hypotheses, not concluded fixes.** No code changes from
  this session.

### Bugs

1. **`$/hr` on the offer/active card is wildly inflated for shop-&-wait offers (~$116/hr on a ~1-hour
   $30 grocery run).** Screenshot: H-E-B offer, gross **$30.03**, card hero reads **`$116/hr`**, sub-line
   **`Net $28.89 · 3.2 mi · $9.03/mi`**, score **86 ("AWESOME OFFER")**. The pickup card on the same
   screen shows **`36:08` to go / pickup by 16:53** — i.e. the *pickup deadline alone* is ~36 min, and
   the dasher estimates the whole job at **almost an hour**. So the real rate is ~**$30/hr**, not $116.
   - **The number is internally consistent — the *time model* is the bug (not an arithmetic slip).**
     The hourly is `netPay / estTimeHours` where
     `estTimeMinutes = (dist × avgMinutesPerMile) + basePickupMinutes`
     (`OfferEvaluator.evaluate`, `domain/.../evaluation/OfferEvaluator.kt:24-29`). With the defaults
     `avgMinutesPerMile = 2.5`, `basePickupMinutes = 7.0`
     (`UserEconomy.kt:105-106`): `3.2 × 2.5 + 7 = 15.0 min = 0.25 h` → `28.89 / 0.25 = $115.56/hr` →
     rounds to the `$116/hr` on screen. The estimate is **15 minutes for a job DoorDash itself says is
     36+ minutes to pickup.**
   - **Likely root cause (hypothesis): the time estimate is a pure distance heuristic with no
     shop/wait component and no use of the platform's own timing signals.** `basePickupMinutes` is a
     single flat 7-min overhead for *both* pickup and dropoff — fine for a hand-it-to-me restaurant
     bag, badly wrong for a **Shop & Deliver / grocery / alcohol** order where in-store shopping +
     checkout + ID-check is the dominant time sink and is **independent of drive distance**. A 25-item
     H-E-B shop and a 1-item McDonald's bag get the *same* 7-min overhead. The model also ignores the
     real signals already on the offer/pickup screen — the **pickup-by deadline** and (for shops) the
     **item count** — which we parse and already carry on the cards (`itemsRemaining`/`itemsShopped`,
     `deadlineMillis` in `FlowCardMapper`).
   - **This very likely also inflates the offer *score*, confirming the dasher's worry that "the
     offer logic is off too."** The same `estTimeMinutes` → `activeHourly` feeds the **`ACTIVE_HOURLY`
     scoring metric** (`OfferEvaluator.kt:140` → `calculateMetricScore` → `:197`,
     `(hourly / target).coerceIn(0,1)`). A 4–5× inflated hourly will peg that metric at its max for
     almost any shop offer, dragging the composite score up — plausibly a big part of why a ~$30/hr-real
     grocery run scored **86 / "AWESOME"**. So this is one model error surfacing in two places (the HUD
     number *and* the accept/decline verdict), exactly because `dollarsPerHour` is the SSOT for both.
   - **Desk findings + developer-confirmed direction (06-20):**
     - **Distance is whole-offer.** Developer confirms `offer.distanceMiles` is (should be) the full
       offer route, not a single leg — so the drive-time term isn't the under-count; the missing
       **shop/wait time** is.
     - **We already parse the platform's own deadline — the evaluator just throws it away.**
       `ParsedOffer` carries `dueByTimeMillis` / `dueByTimeText` (the "Deliver by" time) and it's
       **populated on real DoorDash offers** (corpus e.g. `dueByTimeMillis=1780333740000`,
       `dueByTimeText=5:09 PM` in `approved-parse-output.json`). `ParsedOffer` also has
       `timeToCompleteMinutes`, but that's **only parsed for Uber** (`uber.json:160`) and is **null on
       every DoorDash offer** (DoorDash doesn't surface a "time to complete"). Yet `OfferEvaluator`
       reads only `payAmount` / `distanceMiles` / `itemCount` (`OfferEvaluator.kt:12-14`) — it never
       touches `dueByTimeMillis`. **Direction:** the estimate should parse/anchor on the offer's own
       deadline (derive a delivery window from `dueByTimeMillis − now` on DoorDash;
       `timeToCompleteMinutes` directly on Uber) and fall back to our heuristic only when the platform
       gives us nothing.
     - **Shops get their own time model, item-rate based — not a flat constant.** Developer: a Shop &
       Deliver estimate must be distinct from restaurant/retail *pickup* and must be driven by the
       **dasher's own pick rate (items/minute)**, not a magic number. We already parse the shop
       `itemCount` (corpus: CVS=4, Dollar General=9, Michaels=11…), so the shop term is roughly
       `itemCount ÷ itemsPerMinute` (+ a checkout/ID-check overhead), gated on the
       SHOP_FOR_ITEMS / alcohol recognition we already have. Until we can *measure* a given dasher's
       items/min, seed a **sensible default** rate and let it be refined once we have real shop-duration
       data per dasher (an estimated, eventually-learned metric — a new `UserEconomy` field, default
       now, personalized later).
   - **Status:** Open — direction agreed (parse offer deadline as the primary/anchor signal + a separate
     item-rate shop model with a seeded default), implementation deferred. Acting as field-testing agent
     this session: recorded only, no code changes. When implemented this needs field re-validation that a
     grocery/ACV shop now reads a realistic `$/hr` and a non-inflated score — add a "Next field test"
     checklist item at that point.

### Verification TODOs — crash recovery held in the field (confirmation 2/2)

2. **Crash recovery survived a real mid-checkout power-off and resumed the dash on the correct phase.**
   ~17:01, at the **H-E-B checkout lane**, the dasher **dropped the phone and it powered off** mid
   **checkout/pickup flow** (same H-E-B ACV shop as item #1). On restart, the dasher relaunched
   DoorDash/DashBuddy and the **bubble HUD came back up, recognized it was still on a pickup, and the
   prior (pre-crash) dash resumed** — no new dash, no lost session. This is exactly the `StateManagerV2`
   crash-recovery path (replay observations over the last snapshot) working against a **true cold
   power-loss** (not a process kill) — the strongest version of the test.
   - **Why this is a good characterization case:** the crash landed **inside the checkout/pickup
     confirm window**, which is the phase the dropoff/grace machinery is most sensitive to (the
     06-17/06-19 phantom-dropoff and double-complete investigations all clustered around
     pickup-confirm → dropoff transitions). Recovering *onto a pickup* mid-checkout — rather than
     skipping to dropoff or re-minting the task — is the behavior we want to confirm held.
   - **Second confirmation — held through end of dash (2026-06-20).** After recovery the resumed dash
     stayed intact: the prior dash's history was **all still present**, the dasher **finished the
     delivery and ended the dash normally**, and everything persisted as expected (no lost session, no
     duplicate dash). Two independent in-field confirmations: the recovery itself (resumed onto the
     pickup) and the clean end-of-dash teardown.
   - **Still want the db cross-reference at desk (not blocking the 2/2 — confirmatory).** Logs will be
     reviewed anyway; when they are, verify in `app_state_snapshots` / `app_events` around 17:01: (a) the
     recovery restored the **same** job/task identity (no re-mint, no new sessionId for the resumed
     dash); (b) **exactly one** pickup task for the H-E-B shop across the crash boundary (the checkout
     interruption didn't split or double it); (c) the eventual `DELIVERY_COMPLETED` for this order fires
     **once** and reconciles into session earnings (this delivery was the second-to-last of the dash).
   - **Status:** **Validated — 2/2 field confirmations** (recovery onto pickup + clean end-of-dash with
     full history intact, both 2026-06-20). Desk db cross-reference above remains as a confirmatory check
     during the routine log review, not a blocker. Acting as field-testing agent: recorded only, no code
     changes.
