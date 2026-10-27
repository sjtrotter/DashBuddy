## 2026-07-22 — dash in progress; DoorDash + SECOND Uber attempt (logged live from the field via chat; desk-analyzed 2026-07-22, results inline + at entry end)

- **Date:** 2026-07-22 (entry written mid-dash from the developer's live narration; numbers below are
  as-reported from the field, not yet desk-verified against a data pull)
- **Platform(s) tested:** DoorDash + **Uber (second real attempt — two deliveries actually completed this time)**
- **Branch under test:** desk-confirmed the post-#822 build (07-20 install: #802/#805/#815/#817/#818/
  #820/#821 all present — evidenced by the "Transfer out" marker block, the #815 scrub counters, Room v14
  + projector v7). The dash itself was 2026-07-21 (five sessions 13:10–19:15); the entry was live-logged
  under the 07-22 date the analysis ran.
- **Field conditions:** concurrent DoorDash + Uber. On DoorDash, an H-E-B Shop & Deliver ($34.45 / 10 mi)
  surfaced the items-vs-units parse conflation (see Bug #3). On Uber, offers were received and at least
  two were accepted and delivered to completion.

### Bugs

1. **[HIGH, uber offer lifecycle]** **Every Uber offer this session was recorded as a timeout** —
   including at least two offers that were actually accepted and driven to completed deliveries. One
   hypothesis chain (desk-unverified): the accept was never registered, so each offer's per-offer
   `OFFER_EXPIRY` timer lapsed and resolved it as `OFFER_TIMEOUT`. Two candidate gaps, not mutually
   exclusive: (a) `uber.click.accept_offer` (requires text "Accept"/"Match" on the offer screen) may not
   match how the fielded Uber accept control actually presents — if the accept is a swipe/gesture or the
   node text differs, no click latch is ever set; (b) the click-less D2 inference (leaving
   offer-presentation to `task:active`, i.e. `uber.screen.active_trip`'s `on_job_view`) only infers an
   accept from a non-task `returnFlow` — if the offer overlay vanished into an unrecognized frame or a
   splash/restart gap first, the edge never qualifies. Would need to confirm from the capture pull which
   frame followed each offer and whether any accept-click observation exists at all.
   - **Status:** CONFIRMED, hypothesis (a) — desk 07-22: ZERO `uber.click.accept_offer` observations
     exist; the fielded accept control produces a TEXTLESS click node (17 textless UNKNOWN uber clicks,
     several coincident with offer→trip transitions), so the text-gated rule can never latch. (b) also
     failed as a backstop but only because each offer had ALREADY timed out before `active_trip` appeared.
     Filed **#826** (design pass: non-text accept signal / expiry-grace interplay / #785 notification
     corroboration).
2. **[HIGH, analytics — likely downstream of #1, not independent]** **Two completed Uber deliveries
   produced no records at all.** Consistent with Bug #1's hypothesis: no registered accept → no
   job/tasks minted → no `DELIVERY_COMPLETED` events in `app_events` → the projector has nothing to
   fold; the analytics layer is likely behaving correctly on empty input. Desk check to confirm: the
   Uber session's `app_events` should show offers + timeouts and zero task-lifecycle events. If task
   events DO exist and records are still missing, this becomes its own analytics bug.
   - **Status:** CONFIRMED downstream of #1, with one refinement (desk 07-22): NOT zero task events —
     ONE job was minted from ambient task frames (`job-uber-…-3`, PICKUP_NAV→DELIVERY_CONFIRMED) but
     store "Unknown", uncosted, and never DELIVERY_COMPLETED (session ended early_offline mid-delivery),
     so the projector correctly folded nothing. Analytics is NOT independently buggy; rides #826.
3. **[MEDIUM, offer-engine — filed #823 same-dash]** DoorDash offer count conflates items and units:
   the H-E-B offer showed 64 (units) but ~30 unique items; `parseItemCount` grabs the first number with
   no label discrimination, inflating the shop-time estimate (~80 min vs ~37) and roughly halving the
   displayed $/hr. Desk-verified during the dash from the capture corpus (offers render
   `(9 items • 11 units)`, `(4 items)`, and units-only shapes). Also confirmed: the post-arrival shop
   screen's true counts are parsed live (`itemsRemaining`/`itemsShopped`) and displayed, but the frozen
   accept-time `estMinutes` is never corrected from them.
   - **Status:** Filed as #823 (three-phase plan) + a same-day scope pivot recorded on the issue:
     possibly skip the offer-time list-peek capture dependency and instead re-evaluate at store arrival
     (a natural unassign decision point). Stacked-offers edge case (multiple peek lists) noted there.
4. **[MEDIUM, uber offer parse]** **Uber offer TIME and MILES get swapped** — time interpreted as
   miles and vice versa (developer observed live; which offers/values TBD from the pull). A strong
   desk-side hypothesis from reading `uber.screen.offer`'s parse: the `distance` finder's regex
   `\d[\d.]*\s*mi` **also matches "38 min"** ("mi" is a prefix of "min"), and Uber renders time and
   distance fused in one node (e.g. "38 min (6.2 mi) total"). Both fields' transforms take the
   *leading* number of whatever node their finder lands on (`parseDistance` grabs the first numeric,
   `timeToCompleteMinutes` uses `parseLeadingInt`), so on a fused node whichever value is written
   first wins BOTH fields — "38 min (6.2 mi)" parses as distance=38 *and* time=38; a "6.2 mi (25
   min)"-ordered variant would parse time=6. If this holds, the fix direction might be anchoring the
   distance regex against the `min` suffix (e.g. `mi\b` semantics) and/or extracting each value from
   the capture group adjacent to its own unit rather than the node's leading number — to be confirmed
   against the session's offer captures before touching the rule. Note this also poisons the #762 D2
   economics on any Uber offer that DOES get accepted (est time and distance both wrong → garbage
   $/hr and $/mi), independent of the accept-detection Bug #1.
   - **Status:** Open.
5. **[MEDIUM, uber offer UX]** **The Uber offer read differs from the DoorDash one** — developer
   expects the identical terse format ("quick real-numbers stats") regardless of platform. Desk-side
   finding while logging: the composition path is ALREADY platform-agnostic by design — one effect
   site (`OfferEffects.kt` eval-landed edge → `SpeakOffer` + `PostOfferNotification`) and one TTS
   template (`TtsEffectHandler.formatEvaluation`: verdict, merchant, $/hr, net, miles, score) with no
   platform branching. **Field follow-up same session resolved the fork:** the read WAS spoken —
   that's how the developer noticed minutes being parsed as miles — so the eval-landed edge and the
   shared template both fired correctly, and the divergence was entirely **degraded inputs**, not a
   different code path. Bug #5 therefore mostly collapses into Bug #4 (the time/miles swap poisoning
   the spoken miles and $/hr), PLUS one new data point: **at least one Uber offer spoke the
   "Unknown Store" fallback** — the `uber.screen.offer` `storeName` extraction (an exclusion-list
   TextView finder: not price/"+"/min/Accept/Match/"Delivery"/etc.) missed on a real offer shape.
   Desk check: find that offer's capture, see which node held the store name and which exclusion (or
   node structure) blocked it. The TTS/format layer itself needs NO change — fixes land in the Uber
   parse only (Development Principle 8 holds: no platform branch in the formatter).
   - **Status:** RESOLVED as re-scoped (desk 07-22): the spoken template is byte-identical across
     platforms (receipts in the analysis); the divergence was Bug #4's swapped inputs. The storeName
     miss ("Unknown Store", offer seq 615) rides **#827 Part 2** — its frame was recognition-deduped so
     the missed node needs a fresh capture. Raw addendum ANSWERED — with a dev framing correction
     (2026-07-22): Uber presented each offer ONCE; DashBuddy recognized the ticking card as a stream of
     REPLACEMENT offers (content changes each tick → new offerHash → replace → re-eval → re-SPEAK: 18
     reads / 17 recorded "offers", one store spoken 3×; every "offer" lived only 3–10 s before a
     TIMEOUT("replaced")). No second offer, no template overrun — but the churn is OURS, not Uber's:
     filed **#830** (identity stability / TTS dedup / outcome inflation; #827's parse fix is the
     prerequisite — the swapped ticking minutes are the dominant churn driver). Also note the recorded
     offer/timeout COUNTS for this session are inflated by the churn variants.
   - **Raw addendum (field, verbatim uncertainty — deliberately NOT hypothesized):** the Uber
     spoken read also seemed to carry *more data* than the DoorDash one — or a *second offer* was
     being read — or something else; unclear from the driver's seat. Developer's instruction: let
     the captures speak. Desk check: count `SpeakOffer` firings vs offers presented in the session
     window, and diff the actual spoken strings (Tts DEBUG firehose lines) against the template.

### Field UX context

1. **Uber pay processing lags (~1 hour).** The developer is entering the completed Uber deliveries
   manually via the drill-down correction path (`MANUAL_DELIVERY`, #650) as Uber finishes processing
   each one. Desk check afterwards: the manual rows should carry `MANUAL` basis and attach to the Uber
   session correctly, and (per Bug #2) they'll be the ONLY delivery rows for this session.
2. Contrast with the 07-19 first attempt: this time offers were being **recognized and recorded
   consistently** (albeit as timeouts) — the 07-19 app-instability capture fragility (that entry's
   INFO #4) was not the blocker. Accept detection is now the visible frontier for Uber (#762 D2 / #785
   territory).

### Verification TODOs

1. Post-dash pull: per-offer frame sequence around each accept moment — is there ANY
   `uber.click.accept_offer` observation? What flow did each offer resolve to (unrecognized frame,
   `active_trip`, splash)? Did any `on_job_view` frames appear at all during the two real trips?
2. Check the Uber session's `app_events`: expected shape under the Bug #1 hypothesis is
   OFFER_PRESENTED/OFFER_TIMEOUT pairs only, zero OFFER_ACCEPTED/task-lifecycle rows.
3. Confirm the manual `MANUAL_DELIVERY` corrections reconcile the session's money once Uber finishes
   processing (Σ manual rows vs the app's own session total).

### Desk analysis (2026-07-22, pull `~/dashbuddy/logs/2026/07/22/` — receipts for everything above)

1. **Money path: DoorDash exact to the cent again** — $71.66 / $34.95 / $20.70 all reconcile exactly
   (6 deliveries, 6 single-drop jobs, all DROP_SHARE on OFFER_FROZEN cpm ~$0.35); zero orphans, zero
   unattributed. The Uber session's two **manual** rows carry MANUAL basis, attach to the correct
   session, Σ $15.94 (Field-UX #1 confirmed). 0 ERROR, 0 restarts, 0 NLS disconnects (#731 stays
   parked); projector v7 watermark caught up.
2. **Checklist advanced:** #815 UNKNOWN scrub → 1/2; #810-B1 tripwire false-positive half → 1/2 (zero
   rows on a normal day); #794/#802 Transfer-out block → 1/2. #820/#821 redact surfaces not exercised
   (but `dropoff_handoff` rendered fully masked: name/address/apt all `[redacted:*]`). #762-D2 item
   BLOCKED-as-fielded (see the annotated checklist item; #826/#827 filed).
3. **PII sweep:** three customer-PII leaks in debug captures, no dasher-banking anywhere. NEW class
   instance: `uber.screen.active_trip` (RECOGNIZED) persists a raw customer name as a bare node — no
   rule redact, prefix backstop structurally blind → filed **#825** (HIGH, Pledge). Two DoorDash
   UNKNOWN sheets ("Current dash" overview, "Close sheet" dropoff detail) leaked bare-node
   name/address/gate-unit — the documented #806 direction-1 residual, now with fresh fixture frames in
   the pull (recorded on #806). #815's marker-prefixed scrubs fired correctly on the same frames.
4. **One more #811-family sighting:** a single confirm_decline fail-closed abort (label "Decline
   offer" present but verification refused on a stale frame) — consistent with the refuted-premise
   analysis on #811; disposition still pending there.
5. **Manual-row mileage note (open question):** MANUAL delivery rows (miles=null) inherit
   odometer-partition miles asymmetrically (P. Terry's 1.52 mi vs Thai Buri 12.14 mi, denting the
   latter's net) — expected #650-era behavior, but worth a look when Uber manual entry becomes routine.

**Issues filed this pass:** #825 (uber active_trip redact, HIGH), #826 (Uber accept undetectable — the
D2 blocker), #827 (uber offer time/miles swap + storeName miss), and — after the dev's framing
correction on the triple-read — **#830** (Uber offer identity churn: replacement storm → repeated
reads, inflated offer/timeout counts, destroyed accept window; #827 lands first). **#823** got its
desk receipts (the 64-unit H-E-B offer + all fielded `(N items • M units)` shapes confirmed) and a
design recommendation (2026-07-22 comment): Phase 1 + a learned items:units ratio for units-only
offers ships now; Phase 2 grows into the primary deliverable (arrival re-evaluation + advisory,
frozen-economics-preserving); Phase 3 offer-time peek DEMOTED — the pull proves two deliberate manual
peek taps produced zero distinct capturable frames (the surface may expand in-window and stay
classified as offer_popup), on top of the actuation-consent/mis-tap/scroll/stacked-list costs.
