# ADR-0007: Canonical Domain Schema for Offer Evaluation, Driver Aggregation, and Academic Federation

**Status:** Proposed
**Date:** 2026-05-04
**Builds on:** ADR-0002 (cross-platform state taxonomy), ADR-0005 (multi-region state architecture)
**Related:** RFC #193 (matchers infra), RFC #194 (academic federation), `LEGAL.md`

---

## Context

DashBuddy currently extracts offer / pickup / delivery fields by reasoning
from the DoorDash UI surface — "what does the screen show?". That works for
one platform but bakes platform UI shape into the domain. The state machine,
evaluator, and event log all consume types (`ParsedOffer`, `ParsedOrder`,
`Task`, `Session`) whose field set was driven by what DoorDash happens to
display, not by what the business logic actually needs.

Three downstream consumers need anchors that the current schema only
partially captures:

1. **OfferEvaluator** scores one offer in isolation. Today it has pay,
   distance, item count, deadline, badges. It is missing: separate pickup-by
   vs dropoff-by deadlines on the executing tasks, normalized store identity,
   estimated-vs-historical-actual mileage confidence.
2. **Driver self-aggregation** (the dasher analyzing their own history) needs
   longitudinal anchors that today are missing or implicit: estimate-vs-actual
   mileage deltas, wait-at-store by chain, deadline-hit rate, time-in-zone,
   offers-shown vs accepted, item-pick fidelity, lead-time-to-first-offer.
3. **Academic federation** (RFC #193 / #194) needs anchors that survive PII
   scrub + k=10 + on-device DP and remain useful for studying the visible
   offer surface. Today there is no privacy-class taxonomy on the fields and
   no normalized identifiers (store chain id, zone hash) that would let
   aggregation work across drivers without leaking PII.

This ADR defines a **canonical, platform-agnostic domain schema** anchored on
the questions the business logic and research consumers want answered. It is
the precursor design for RFCs #193 and #194 and aligns with the framing
pledge in `LEGAL.md`: empirical measurement of the visible offer surface,
never reverse-engineering of the assignment system.

---

## Driving Questions

The schema falls out of the questions we want it to answer. We list the
questions first, then derive the fields. **If a field does not serve at
least one listed question, it should not exist; if a question cannot be
answered, the schema is missing something.**

### Dasher on-the-job (live HUD)

Continuously-derived values surfaced while a job is in progress.

- Pickup-by slack and dropoff-by slack, ticking down.
- Count-up since arrival at store; count-up since offer accepted.
- Item count, drop instruction, and store/customer addresses for the active
  task (so the dasher does not have to thumb back through screens).
- This chain's historical median dwell at this store ("you usually wait
  9 min at this Chipotle").
- This drop-address's prior tip-after history (privacy: hashed address).
- Live $/mi gauge: this offer's promised $/mi vs the rolling-week average.
- Live deadhead ratio for the dash: idle miles / total miles so far.
- Stacked-offer next-leg distance — surfaces traps where stack B is far
  from drop A.
- Pace-to-target: live $/hr vs a dasher-set target for the session.

### Dasher off-the-clock (stats page, between shifts)

A "your numbers, with the gig app as source of truth" stats surface,
disclaimed accordingly.

**Earnings validation.** Offered total vs actual paid (per job; flag
mismatches); offered tip vs final tip; offered miles vs actual miles;
offered time-on-job vs actual.

**Behavioral / efficiency.** Item-picking ratio (items found / items
requested) per shop, by chain, over time. Substitution and refund rates
(shop-and-deliver). Dwell distribution by chain. Deadhead ratio per session.
$/active-hour vs $/online-hour gap (the idle drag). Decline-streak effects.
Tip-after-delivery delta by chain, zone, time-of-day. Acceptance- and
completion-rate progress against status thresholds. Cross-platform $/hr
delta.

**Predictive.** Best day-of-week × hour-of-day heatmap of $/hr. Best zones
*for this dasher specifically*. Lead-time-to-first-offer broken by
start-context: cold-start in hot zone, cold-start in cold zone, scheduled
in assigned zone, scheduled but elsewhere, mid-shift resume after pause.
Acceptance-threshold counterfactual ("raising your threshold to $1.00/mi
would have netted +$X / −Y hours"). Vehicle wear-and-tear projection at a
custom $/mi.

### Academic — empirical measurement of the visible offer surface

Framing reminder per `LEGAL.md`: every question below is phrased as
measurement of what the dasher already sees on screen, not characterization
of platform internals.

**Labor economics.** Effective hourly wage including unpaid waiting,
distributed across drivers and zones. Share of presented offers that would
yield sub-minimum-wage if accepted. Wage trajectory by tenure. Platform
response to local minimum-wage law changes (natural-experiment time series).
Tips-as-wage-substitution: base/tip ratio over time. Effective fuel-and-
vehicle cost share of gross pay. Multi-app captivity vs freedom under AR
pressure. Spatial wage inequality with census-tract overlay.

**Logistics / operations research.** Empirical dwell-time distribution by
store category. Mileage savings of batched offers vs single (efficiency
frontier). Offered-route vs shortest-path delta. ETA accuracy as a function
of TOD and zone density. Empirical offer-availability per zone-hour as a
supply/demand signal. Latency from offer-accept to store-arrival.
Bottleneck localization: restaurant prep, apartment handoff, routing.

**Computer science (HCI / systems / privacy).** Matcher hit-rate over time
as a leading indicator of platform UI changes. Frequency and shape of UI
A/B tests visible from outside. Decision-deadline adequacy under cognitive-
load models. Mistap rate on offer screens. Notification cadence vs accept
rate. Privacy preservation of on-device cohort + DP aggregation under
realistic adversary models (DashBuddy itself as testbed). Accessibility
coverage of the platform UI.

**Social science.** Ambient algorithmic pressure: how do countdown timers
and AR penalties shape decisions? Visibility politics: what does the
platform show vs hide? Tipping-screen vs hidden-tip-screen behavioral
delta. Worker triangulation strategies under information asymmetry.
Spatial sorting: who gets the good zones?

**Public-health adjacent.** Consecutive-online-time distribution per session
(fatigue proxy). Late-night shift prevalence by region. Break-frequency
proxies (paused-state durations).

**Geography / urban planning.** Street-level delivery-demand heatmap.
Food-desert / delivery-desert overlap. Delivery vs transit-access
correlation.

**Climate / energy.** VMT per delivery, aggregated. Deadhead-miles share
of total VMT (environmental cost of supply mismatch). Emissions per
delivery as calibration vs platform-claimed numbers.

---

## Decision

Adopt a four-type canonical schema — `Offer`, `Job`, `Task`, `Session` —
with `Order` nested under `Offer`, and a forward-looking `ScheduledShift`
placeholder. Tag every field with a **lens utility** ({Evaluator, SelfAgg,
Federation}) and a **privacy class** that governs what crosses the device
→ aggregator boundary.

The schema is justified field-by-field against the driving questions
above. Implementation lands in a separate branch series — this ADR is the
spec.

---

## Relationships

How the four types fit together:

- **Offer ↔ Job.** An accepted offer becomes a job. The job links back to
  its parent offer via `parentOfferHash`. Both are kept: the offer is the
  immutable decision-time snapshot; the job is the lived-execution record.
- **Order informs Job.** An order is a *description* of what is expected
  (merchant, items, customer area). It rides under the offer at decision
  time and informs how we anticipate the job. Once the offer is accepted,
  the order's content is what tells the dasher what they are about to do.
- **Tasks compose into Job.** Tasks (pickup, dropoff) are the lived steps
  inside a job; they compose to job-level totals (actual miles, total
  dwell, on-time rate). Tasks belong to the job, not the offer.
- **Job ↔ Session.** Every job links to the session it executed inside
  via `Session.sessionId`. Lets us answer "what fraction of session N was
  paid work" with one join.
- **Session ↔ ScheduledShift (future).** A session may be linked to a
  shift the dasher pre-scheduled on the platform. Fuzzy-matched at
  session-start time within ±1 hour.

Some data is intentionally shared / denormalized across types when it
makes the consumer simpler (e.g., the offer's overall delivery deadline
appearing on the last dropoff task). Goal: normalization where it helps
extraction and analysis, not normalization for its own sake.

---

## Three-Lens Framing

Every field is tagged with:

- A **lens utility**: {Evaluator (E), SelfAgg (S), Federation (F)}.
- A **privacy class**:
  - `LOCAL_ONLY` — never leaves the device (raw extracted text, exact GPS,
    customer name hash, customer address hash, store address).
  - `DP_NUMERIC` — numeric, shareable through DP-budgeted aggregations
    (payAmount, distanceMiles, durations, timestamps).
  - `K_ANON_COHORT` — categorical, shareable only when k≥10 cohort holds
    (storeLocationId, zoneId, customerCityRegionHash).
  - `PUBLIC` — non-sensitive metadata (platform name, national-chain
    storeChainId, offer badges).

On-device retention is unrestricted; the privacy class only governs the
device → aggregator boundary.

---

## Canonical Schema

### Offer — the unit at decision time

What the evaluator scores and what the federation studies. Captured exactly
once, at presentation, then immutable.

> **State-layer note (#438 B3, 2026-07-06).** This ADR's "offer shape should be
> a list, not a scalar" constraint (N≥1 concurrent offers) is now *satisfied at
> the state layer*: `PlatformRegion.pendingOffers: List<PendingOffer>` replaced
> the single global `FlowRegion.pendingOffer` scalar (see
> `docs/design/multiplatform-correctness-pack.md` §Item 7). The per-offer expiry
> (hash-carrying `OFFER_EXPIRY` timer) and click→offer correlation contracts are
> locked there, so populating N>1 per platform is ruleset/parser work (#251) with
> zero `:core:state` edits expected. This ADR's own *canonical persisted* schema
> below is unaffected and **stays Proposed** — its implementation remains #245's
> series; this note only records that the runtime slot is no longer scalar.

The Offer carries **offer-wide facts only**: total pay, total estimated
distance, total estimated duration, total item count, an overall delivery
deadline (the "Deliver by HH:MM" shown on the offer screen), and the
decision-window countdown. Per-task deadlines, per-task estimated mileage,
and per-task estimated time do **not** live on the Offer — those are
populated on `Task` once the job has started and the in-task screens are
seen. (Per-task estimated mileage / time from the in-task nav screen are
*intentionally not captured*; see Open Questions.)

Every presented offer is recorded with a **decision** ({ACCEPT, DECLINE,
TIMEOUT}) and full features regardless of decision. This is the single
biggest unlock for the academic and self-counterfactual questions — the
declined offers are most of the visible surface.

| Field | Type | Lens | Privacy | Notes |
|---|---|---|---|---|
| `offerHash` | String | E,S,F | PUBLIC | Existing. Dedup across re-presentations. |
| `platform` | Platform | E,S,F | PUBLIC | Currently implicit; promote to stored offer. |
| `presentedAt` | Long | E,S,F | DP_NUMERIC | When the offer screen first appeared. |
| `decisionDeadlineMillis` | Long? | E | DP_NUMERIC | When the offer itself expires. From `initialCountdownSeconds + presentedAt`. |
| `decision` | OfferDecision | E,S,F | PUBLIC | **New.** ACCEPT / DECLINE / TIMEOUT. Persisted for every presented offer. |
| `decidedAt` | Long? | S,F | DP_NUMERIC | **New.** When the dasher acted. Decision latency = `decidedAt − presentedAt`. |
| `payAmount` | Double? | E,S,F | DP_NUMERIC | Existing. |
| `payComponents` | PayBreakdown? | E,S,F | DP_NUMERIC | **New.** Base/tip/peak split when shown on the offer screen. |
| `estimatedDistanceMilesTotal` | Double? | E,S,F | DP_NUMERIC | Existing as `distanceMiles`. Renamed for clarity. |
| `estimatedDurationMinutesTotal` | Long? | E,S,F | DP_NUMERIC | Existing as `timeToCompleteMinutes`. Renamed for clarity. |
| `deliverByMillis` | Long? | E,S,F | DP_NUMERIC | **New.** The single overall "Deliver by HH:MM" shown on the offer screen. Equals the last-dropoff `Task.deadlineMillis`. |
| `itemCountTotal` | Int | E,S,F | DP_NUMERIC | Existing. |
| `offerType` | OfferType | E,S,F | PUBLIC | **New enum.** SINGLE / BATCHED / SHOP / RETAIL / MIXED. |
| `orders` | List\<Order\> | E,S,F | mixed | Existing (nested). |
| `badges` | Set\<OfferBadge\> | E,S,F | PUBLIC | Existing. |
| `surgeMultiplier` | Double? | E,S,F | DP_NUMERIC | **New.** Peak-pay / surge multiplier on the offer screen. |
| `presentedZoneId` | String? | S,F | K_ANON_COHORT | **New.** Hashed zone identifier at presentation time. |
| `rawExtractedText` | String? | — | LOCAL_ONLY | Existing. Reprocessing only. |

### Order — one merchant within an offer

Lives nested under `Offer`. One offer can have multiple orders (batched).

| Field | Type | Lens | Privacy | Notes |
|---|---|---|---|---|
| `orderIndex` | Int | E,S,F | PUBLIC | Existing. |
| `orderType` | OrderType | E,S,F | PUBLIC | Existing. |
| `storeName` | String | S,F | K_ANON_COHORT | Existing. |
| `storeChainId` | String? | E,S,F | PUBLIC* | **New.** Canonicalized chain id (`chipotle`, `walmart`). National chains: PUBLIC. Independents: K_ANON_COHORT. |
| `storeLocationId` | String? | S,F | K_ANON_COHORT | **New.** `sha256(chainId + canonicalAddress)`. |
| `storeAddress` | String? | S | LOCAL_ONLY | **New.** Public info but identifies the dasher's route. |
| `storeCityRegionHash` | String? | F | K_ANON_COHORT | **New.** Coarse (zip3 or H3 r5) for federation. |
| `estimatedMilesToStore` | Double? | E,S | DP_NUMERIC | **New.** Per-leg mileage if shown on the offer screen. |
| `itemCount` | Int | E,S,F | DP_NUMERIC | Existing. |
| `isItemCountEstimated` | Boolean | S | PUBLIC | Existing. |
| `subtotalCents` | Int? | S | LOCAL_ONLY | **New.** Order subtotal when shown — proxy for tip-percent inference. |
| `badges` | Set\<OrderBadge\> | E,S,F | PUBLIC | Existing. |

### Job — an accepted offer in execution

Created when offer is accepted. Holds the link from decision-time `Offer`
to the lived experience captured as `Task`s.

| Field | Type | Lens | Privacy | Notes |
|---|---|---|---|---|
| `jobId` | String | E,S,F | PUBLIC | Existing. |
| `parentOfferHash` | String | E,S,F | PUBLIC | Existing. |
| `sessionId` | String | S,F | PUBLIC | **New (explicit).** FK to the Session this job ran inside. |
| `acceptedAt` | Long | S,F | DP_NUMERIC | Existing as `startedAt`. Optional rename for clarity. |
| `completedAt` | Long? | S,F | DP_NUMERIC | **New.** Currently implicit in PostTask. |
| `actualPayAmount` | Double? | S,F | DP_NUMERIC | **New.** From PostTask payout — may differ from offered. |
| `actualTipAmount` | Double? | S,F | DP_NUMERIC | **New.** Final tip after any post-delivery adjustment. |
| `tasks` | List\<Task\> | S,F | mixed | The pickup/dropoff tasks executed. (Currently `recentTasks` lives on `PlatformRegion`; see Open Questions.) |

### Task — one pickup or dropoff within a job

Existing concept; today already carries `phase`, `arrivedAt`, and odometer
anchors. Additions make the per-phase lifecycle (nav start → arrival →
departure) explicit, and add the exception flags + item-pick fidelity
needed for the question list.

| Field | Type | Lens | Privacy | Notes |
|---|---|---|---|---|
| `phase` | TaskPhase | S,F | PUBLIC | PICKUP / DROPOFF. Existing. |
| `orderRef` | String | S,F | PUBLIC | Index/key into parent offer's `orders`. |
| `deadlineMillis` | Long? | S,F | DP_NUMERIC | Existing. The pickup-by or dropoff-by that applies to this task, captured from the in-task nav screen. |
| `enteredNavigationAt` | Long? | S,F | DP_NUMERIC | **New.** When this task's nav screen first appeared. |
| `arrivedAt` | Long? | S,F | DP_NUMERIC | Existing. |
| `platformArrivedDisplay` | Long? | S | LOCAL_ONLY | **New.** Platform-claimed arrival timestamp ("Arrived at 7:42") — sanity-check vs our pipeline. |
| `departedAt` | Long? | S,F | DP_NUMERIC | **New.** When the task ended (left store / completed handoff). |
| `odometerAtNavStart` | Double? | S | LOCAL_ONLY | Existing as `odometerAtEntry`, renamed for clarity. |
| `odometerAtArrival` | Double? | S | LOCAL_ONLY | Existing. |
| `odometerAtDeparture` | Double? | S | LOCAL_ONLY | **New.** |
| `actualMilesThisTask` | Double? | S,F | DP_NUMERIC | Derived: `arrivalOdo − navStartOdo`. |
| `dwellMinutes` | Long? | S,F | DP_NUMERIC | Derived: `departed − arrived`. |
| `onTimeAtPoint` | Boolean? | S,F | PUBLIC | Derived: `arrivedAt ≤ deadlineMillis`. |
| `dropInstruction` | DropInstruction? | S,F | PUBLIC | **New.** HAND_TO_ME / LEAVE_AT_DOOR / etc. Affects expected dwell. |
| `itemsRequested` | Int? | S,F | DP_NUMERIC | **New.** Pickup phase, shop-and-deliver only. |
| `itemsFound` | Int? | S,F | DP_NUMERIC | **New.** Picking ratio numerator. |
| `itemsSubstituted` | Int? | S,F | DP_NUMERIC | **New.** |
| `itemsRefunded` | Int? | S,F | DP_NUMERIC | **New.** |
| `hadShortage` | Boolean | S,F | PUBLIC | **New.** Exception flag. |
| `hadCustomerEdit` | Boolean | S,F | PUBLIC | **New.** Customer modified order mid-shop. |
| `hadSupportInteraction` | Boolean | S,F | PUBLIC | **New.** Driver had to engage support during this task. |
| `redCardTotal` | Double? | S | LOCAL_ONLY | Existing. |
| `customerNameHash` | String? | S | LOCAL_ONLY | Existing (dropoff only). |
| `customerAddressHash` | String? | S | LOCAL_ONLY | Existing (dropoff only). Used for repeat-address tip history on-device. |
| `customerCityRegionHash` | String? | F | K_ANON_COHORT | **New.** Coarse hash for federation. |

### Session — an online interval on one platform

Aggregates from `Job`s and the always-on odometer.

| Field | Type | Lens | Privacy | Notes |
|---|---|---|---|---|
| `sessionId` | String | S,F | PUBLIC | Existing. |
| `platform` | Platform | S,F | PUBLIC | Existing. |
| `startedAt`, `endedAt` | Long, Long? | S,F | DP_NUMERIC | Existing. |
| `startContext` | StartContext | S,F | PUBLIC | **New.** COLD_START / SCHEDULED / RESUMED_FROM_PAUSE. Required for the lead-time-to-first-offer question. |
| `scheduledShiftId` | String? | S,F | PUBLIC | **New (forward-looking).** FK to a `ScheduledShift` row when fuzzy-matched at start. |
| `earningsTotal` | Double | S,F | DP_NUMERIC | Existing. |
| `milesOnline` | Double | S,F | DP_NUMERIC | **New.** Odometer delta while online. |
| `milesActive` | Double | S,F | DP_NUMERIC | **New.** Sum of task mileage. |
| `milesIdle` | Double | S,F | DP_NUMERIC | Derived: online − active. |
| `activeJobMinutes` | Long | S,F | DP_NUMERIC | **New.** Time spent inside accepted-job states. |
| `awaitingOfferMinutes` | Long | S,F | DP_NUMERIC | **New.** Time in AwaitingOffer. |
| `pausedMinutes` | Long | S,F | DP_NUMERIC | **New.** Time in DashPaused / PausedOrInterrupted. |
| `sensitiveScreenMinutes` | Long | S,F | DP_NUMERIC | **New.** Time spent on sensitive-matcher hits (banking / ID / support). |
| `overheadMinutes` | Long | S,F | DP_NUMERIC | **New.** Residual unaccounted online time. |
| `surgeMinutesAtStart` | Long? | S,F | DP_NUMERIC | **New.** Was surge active at session start, and for how long? |
| `offersOffered` | Int | S,F | DP_NUMERIC | **New.** Counts derived from per-offer decision records. |
| `offersAccepted` | Int | S,F | DP_NUMERIC | **New.** |
| `offersDeclined` | Int | S,F | DP_NUMERIC | **New.** |
| `offersTimedOut` | Int | S,F | DP_NUMERIC | **New.** |
| `firstOfferLatencyMillis` | Long? | S,F | DP_NUMERIC | **New.** `firstOffer.presentedAt − session.startedAt`. Cold-start vs scheduled-start lead time question. |
| `zoneIntervals` | List\<ZoneInterval\> | S,F | K_ANON_COHORT | **New.** (zoneId, enteredAt, exitedAt). |

### ScheduledShift — forward-looking placeholder

Captured from the platform's schedule screen when visited; fuzzy-matched
to sessions at start time. The full UI feature is out of scope for this
ADR — only the placeholder field on `Session` and the table shape are
defined here.

| Field | Type | Lens | Privacy | Notes |
|---|---|---|---|---|
| `shiftId` | String | S,F | PUBLIC | Local id. |
| `platform` | Platform | S,F | PUBLIC | |
| `startMillis` | Long | S,F | DP_NUMERIC | Scheduled start. |
| `endMillis` | Long | S,F | DP_NUMERIC | Scheduled end. |
| `zoneName` | String? | S,F | K_ANON_COHORT | Assigned zone if shown. |
| `cancelled` | Boolean | S,F | PUBLIC | Soft-delete when the platform's schedule screen no longer shows it. |
| `createdAt` | Long | — | — | Local audit. |

---

## Capture Inventory: Interstitial Harvest

Many of the fields above are only visible on **interstitial screens** —
on-screen for a few seconds during transit, then gone. If we miss the
capture window, the data is lost. The pattern is **save the anchor, derive
the value live** (consistent with the reactive UI principles in `CLAUDE.md`).

| Screen / event | Fields harvested |
|---|---|
| Offer card | All `Offer` fields, `Order` fields, `subtotalCents` if shown, `surgeMultiplier`, `deliverByMillis`, `payComponents` if expanded |
| Decision (accept / decline / timeout) | `decision`, `decidedAt` |
| To-store nav | `Task.enteredNavigationAt` (pickup), `Task.deadlineMillis` (pickup-by), order-detail interstitial fields, `Task.dropInstruction` if shown |
| Arrived at store | `Task.arrivedAt`, `Task.platformArrivedDisplay`, `Task.odometerAtArrival` |
| In-store / shop flow | `itemsRequested`, `itemsFound`, `itemsSubstituted`, `itemsRefunded`, `hadShortage`, `hadCustomerEdit` |
| Departed store | `Task.departedAt`, `Task.odometerAtDeparture`, `Task.dwellMinutes` |
| To-customer nav | `Task.enteredNavigationAt` (dropoff), `Task.deadlineMillis` (dropoff-by — should equal `Offer.deliverByMillis` for the last drop) |
| Drop confirmation / photo | `Task.departedAt` for dropoff (job complete), `Task.platformArrivedDisplay` for dropoff arrival if shown |
| Post-task earnings | `Job.actualPayAmount`, `Job.actualTipAmount`, `Job.completedAt` |
| Schedule screen | `ScheduledShift` row (insert / update / soft-delete) |
| Sensitive screen entry/exit | `Session.sensitiveScreenMinutes` accumulator |
| State entry/exit | `Session.activeJobMinutes`, `awaitingOfferMinutes`, `pausedMinutes`, `overheadMinutes` accumulators |

The matcher layer is the capture site. Each screen-typed matcher emits the
fields it can pull from its own `UiNode` tree; the rest fall to derived /
computed fields.

---

## Schema Diff Against Existing Types

Additions and suggested clarifications. Names are suggestions —
implementation can pick the final spelling. (Code changes are out of scope
for this ADR; this is the spec.)

**`domain/.../model/offer/ParsedOffer.kt`**
- Add: `platform`, `presentedAt`, `decisionDeadlineMillis`, `decision`,
  `decidedAt`, `payComponents`, `surgeMultiplier`, `presentedZoneId`,
  `offerType`, `deliverByMillis`.
- Rename for clarity: `distanceMiles` → `estimatedDistanceMilesTotal`;
  `timeToCompleteMinutes` → `estimatedDurationMinutesTotal`.
- The existing field `dueByTimeMillis` becomes `deliverByMillis` — semantics
  unchanged; the rename makes it match what the UI actually shows.
- Keep as-is: `offerHash`, `itemCount`, `badges`, `initialCountdownSeconds`,
  `orders`, raw text fields.

**`domain/.../model/order/ParsedOrder.kt`**
- Add: `storeChainId`, `storeLocationId`, `storeAddress`,
  `storeCityRegionHash`, `estimatedMilesToStore`, `subtotalCents`.

**`domain/.../state/Task.kt`**
- Add: `enteredNavigationAt`, `departedAt`, `odometerAtDeparture`,
  `platformArrivedDisplay`, `dropInstruction`, `itemsRequested`,
  `itemsFound`, `itemsSubstituted`, `itemsRefunded`, `hadShortage`,
  `hadCustomerEdit`, `hadSupportInteraction`, `customerCityRegionHash`.
- Derived properties: `actualMilesThisTask`, `dwellMinutes`, `onTimeAtPoint`.
- Rename `odometerAtEntry` → `odometerAtNavStart` (disambiguates "started
  navigation" vs "arrived at point").

**`domain/.../state/Job.kt`**
- Add: `sessionId`, `completedAt`, `actualPayAmount`, `actualTipAmount`,
  `tasks` (list).
- Move `recentTasks` from `PlatformRegion` onto the parent `Job` so task
  history is job-scoped rather than region-scoped. (See Open Questions.)

**`domain/.../state/PlatformRegion.kt` Session**
- Add: `startContext`, `scheduledShiftId`, `milesOnline`, `milesActive`,
  `milesIdle` (derived), `activeJobMinutes`, `awaitingOfferMinutes`,
  `pausedMinutes`, `sensitiveScreenMinutes`, `overheadMinutes`,
  `surgeMinutesAtStart`, `offersOffered`, `offersAccepted`, `offersDeclined`,
  `offersTimedOut`, `firstOfferLatencyMillis`, `zoneIntervals`.

**`domain/.../evaluation/OfferEvaluation.kt`**
- Add: `confidenceBand`, low/high estimated duration bounds,
  `historicalChainDwellMinutes`. Surfaces uncertainty driven by the dasher's
  own estimate-vs-actual history at this chain.

**New types**
- `enum class OfferDecision { ACCEPT, DECLINE, TIMEOUT }`
- `enum class OfferType { SINGLE, BATCHED, SHOP, RETAIL, MIXED }`
- `enum class StartContext { COLD_START, SCHEDULED, RESUMED_FROM_PAUSE }`
- `enum class DropInstruction { HAND_TO_ME, LEAVE_AT_DOOR, MEET_OUTSIDE, MEET_AT_DOOR, OTHER }`
- `enum class ConfidenceBand { LOW, MEDIUM, HIGH }`
- `enum class PrivacyClass { LOCAL_ONLY, DP_NUMERIC, K_ANON_COHORT, PUBLIC }`
- `data class PayBreakdown(base: Double?, tip: Double?, peakPay: Double?)`
- `data class ZoneInterval(zoneId: String, enteredAt: Long, exitedAt: Long?)`
- `data class ScheduledShift(...)` per the table above.
- `StoreCanonicalizer.canonicalize(name, address) → (storeChainId, storeLocationId)`
  reusing the existing `sha256` transform in `app/rules/TransformRegistry.kt`.

---

## Aggregation Catalog

What each lens can compute from this schema. Grounded checklist for "does
the schema actually serve the question?"

### Evaluator (per-offer, on-device)
- Net pay = `payAmount − estimatedDistanceMilesTotal × economy.fuelCostPerMile`.
- $/mi, $/hr.
- Confidence band = function of dasher's own historical
  `actualMilesThisTask / estimatedMilesToStore` ratio variance for this
  `storeChainId`.
- Adjusted ETA = `estimatedDurationMinutesTotal + median(dwellMinutes) at this storeChainId`.
- Decision pressure = `decisionDeadlineMillis − now()`.
- Repeat-address tip-history recall (drop-only, by `customerAddressHash`).

### Driver self-aggregation (per-driver, longitudinal, on-device)
- Per-chain: avg dwell, avg payout, avg $/mi, decline rate, on-time rate,
  item-pick ratio.
- Per-zone: $/online-hour, miles-per-hour, offer presentation rate.
- Per-time-of-day: same as per-zone.
- Estimate-vs-actual: distribution of
  `actualMilesThisTask − estimatedMilesToStore` by chain, by zone.
- Deadline performance: distribution of `arrivedAt − deadlineMillis` per
  task phase.
- Lead-time-to-first-offer broken by `Session.startContext`.
- Idle drag = $/active-hour vs $/online-hour delta.
- Tip-after-delivery delta = `actualTipAmount − payComponents.tip`.
- Decline-streak counterfactual = backtest higher acceptance threshold
  against the recorded decline ledger.

### Academic federation (cross-driver, k=10, DP-budgeted)
- Distribution of $/mi by hour-of-day, by region (zoneId-level k=10 cohort).
- Distribution of dwell-minutes by `storeChainId` (national chains: no k=10
  needed; independents: K_ANON_COHORT enforced).
- Estimate-vs-actual mileage gap distribution by region.
- Decline-rate by offer feature (badges, offerType, $/mi bucket) — counts
  only.
- Offer presentation rate per online-hour by zone.
- Lead-time-to-first-offer distribution by zone × TOD × startContext.
- Effective-wage-including-idle distribution by zone.
- Surge persistence: distribution of `surgeMinutesAtStart` by zone × TOD.
- Tip-base ratio distribution; tip-adjustment delta distribution by chain.
- **Never** computed: per-customer anything; per-store-location for
  non-cohort-met locations; raw text; exact GPS.

All federation queries are **counts and numeric distributions of fields the
dasher already saw on screen** — consistent with the `LEGAL.md` framing.

---

## Open Questions

1. **`recentTasks` location.** Move from `PlatformRegion` to `Job.tasks`?
   Cleaner conceptually (task history is job-scoped) but it is a
   region-boundary change. Decision deferred to the implementing PR.
2. **Per-task estimated mileage / time from in-task nav screens.** Currently
   *not* captured. The argument: those values measure the platform's nav
   engine (traffic-adjusted), which is not what we care about. The signal
   that matters is `arrivedAt` vs `deadlineMillis` (deadline performance) and
   `Job.actualMilesTotal` vs `Offer.estimatedDistanceMilesTotal` (offer
   calibration). Revisit if a concrete consumer appears.
3. **`storeChainId` canonicalization.** Simple normalized-name lookup table,
   or embedding-based? Start with lookup; defer.
4. **`storeCityRegionHash` granularity.** zip3 vs H3 resolution-5? H3 is
   more uniform; zip3 is more interpretable. Defer to RFC #193.
5. **DP budget mechanics.** Per-field, per-driver, per-time-window? Defer
   to RFC #194.
6. **`Offer.payComponents` parsing.** Always parse, or only when the UI
   surfaces the breakdown? Recommendation: parse when surfaced, leave
   nullable.
7. **State-duration accounting.** Should `Session.{activeJobMinutes,
   awaitingOfferMinutes, ...}` be live counters maintained by the state
   machine, or computed at session-end from the event log? Live counters
   are cheaper to query but couple state-machine plumbing to schema fields;
   event-log derivation is purer but requires the event log to be
   queryable. Decision deferred.
8. **`ScheduledShift` UX.** Out of scope for this ADR. Filed as its own
   feature issue, blocked by this one — only the placeholder field on
   `Session` and the table shape are agreed here.

---

## Verification

This is a design artifact. Verification is review, not test runs:

- Walk every field in the canonical schema and confirm it has at least one
  consumer in the Aggregation Catalog or Driving Questions list. If a field
  has no consumer, drop it.
- Walk every aggregation in the catalog and confirm every input field
  exists in the schema. If an input is missing, add it.
- Walk every existing field in `ParsedOffer` / `ParsedOrder` / `Task` /
  `Session` and confirm it has either a mapping in the canonical schema or
  a deletion rationale.
- Confirm every `LOCAL_ONLY` field is genuinely never read by code paths
  that cross the device boundary.
- Confirm every academic-federation aggregation can be phrased as
  "distribution / count of a field the dasher already saw on screen" — no
  inference of internal platform mechanics. Cross-check with `LEGAL.md`.

When this ADR is committed: `./gradlew testDebugUnitTest` should still pass
unchanged (no code touched yet). Implementation PRs that follow will run
`*AllMatchersSuite*` per `CLAUDE.md`.

---

## Cross-references

- ADR-0002 cross-platform state taxonomy — the `Platform` enum and state
  classes referenced throughout this ADR.
- ADR-0005 multi-region state architecture — `PlatformRegion` and `Task`
  slots that this ADR extends.
- RFC #193 (matchers infra) — consumes `storeChainId`, `storeLocationId`,
  `presentedZoneId` for the matcher OTA story.
- RFC #194 (academic federation) — consumes the privacy-class taxonomy and
  the Aggregation Catalog as its query surface.
- Issue #141 monetization plan — the managed-cloud sync tier ingests the
  same canonical schema.
- `LEGAL.md` — framing pledge enforced field-by-field in the federation
  lens.
