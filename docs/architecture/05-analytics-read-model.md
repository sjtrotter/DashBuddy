> **Detailed architecture reference — moved out of `CLAUDE.md` on 2026-09-07 (context-budget trim v3).**
> This file is the full, receipt-level narrative for this layer: every rule, its issue/PR receipt, and the
> reasoning that produced it. `CLAUDE.md` keeps the short summary an agent needs every session; this file is
> where a change's history and invariants are recorded in depth. **The 'every PR ships with context updates'
> rule applies here exactly as it does to `CLAUDE.md`:** when a PR changes an invariant described below, update
> this file in the same PR (and the `CLAUDE.md` summary if it goes stale).
> A `§N` reference below means the sibling numbered file here (or its `CLAUDE.md` summary).

# Analytics Read-Model (`core/data/.../analytics/`, `core/database/.../analytics/`, #314)

Analytics is a **CQRS read-model** projected from the durable `app_events` log — the event log is the
source of truth; the read-model tables are a rebuildable cache. **Ordering contract (#732,
dev-decided Option B — no re-stamp):** `sequenceId` is the authoritative fold/order key; `occurredAt`
may lag it (sole carrier: `PICKUP_CONFIRMED`, whose `occurredAt` is the grace-armed
`Task.completedAt`). Consumers order by `sequenceId`; "when did it really happen" reads the payload's
own domain timestamp. KDoc at `AppEventEntity`; `GracedCommitOrderingInvariantTest` trips any silent
re-stamp. `AnalyticsProjector` (`:core:data`, started from `DashBuddyApplication`,
runs every launch off-main + supervised) folds `app_events` → `delivery_records`/`session_records`/
`offer_records` via the pure `RecordFolds`/`SessionFoldContext` (`:domain`). The fold is
**exactly-once** — records + a watermark advance in one `db.withTransaction`, record PKs are the
source `sequenceId` (REPLACE-idempotent) — so the one-time backfill is just the first drain from
watermark 0, and a `PROJECTOR_VERSION` bump wipes + refolds the whole log (rebuild ≡ backfill).
Realized inputs come from the log: pay from `DeliveryPayload.dropRealizedPay`/`totalPay` (#528),
else — when the WHOLE job was receipt-less (a shop order shows no per-delivery receipt; an
out-of-zone "Dash Along the Way" start shows none at all, #999) — a `PayBasis.OFFER_PAY` ESTIMATE
from `DeliveryPayload.offerPayShare`, the accepted offer's quote split across the job's owed drops
at the mint site, consumed by the fold only if no sibling drop already folded a real receipt (#691).
**A receipt with no itemization still prices its drops (#1029):** `apportion(parsedPay = null, …)`
returns an EMPTY map even for a SINGLE drop, so DoorDash 8.93.7's id-less receipt left `parsedPay`
null on every fielded receipt — every drop fell to an `OFFER_PAY` estimate, `payoutStoreForms` never
minted and the #653 guard was off, silently, because `totalPay` still resolved.
`ParsedFieldsFactory.buildPostTask` now SYNTHESIZES `ParsedPay`
from the receipt's own parsed scalars on that layout (empty line items + `totalPay > 0` + a
non-null `customerTips` — the tips line is exposed only while the breakdown is visible, which keeps
every COLLAPSED receipt at `parsedPay == null` as `sameTaskCollapsedDowngrade` requires); the
synthetic tip carries a BLANK type so `injectiveTipMatch` declines and a stacked job even-splits.
Per-store tip itemization off the flat 8.93.7 row is #1051.
**The split is store-correspondence-attributed and consolidation-aware (#996/#997 — a pure mint-site
change; historical rows refold byte-identically). Two mint sites, two policies:** the INLINE
(PostTask-exit) mint, whose job may still be OPEN, keeps the conservative pooled split over the
QUOTED owed orders (`OfferPayFallback.shareFor` — attributing earlier could make Σ stamped >
Σ quoted). The whole policy runs ONCE at the **terminal close**
(`OfferPayFallback.closeAttribution`), in order: (1) the #996 **eligible-owed shrink** — when the
close proves the job complete (`isJobPhysicallyComplete`; the #749 per-customer coverage arm IS the
consolidation proof), every dropoff that **can never mint** (`Task.isNeverActivatedPlaceholder`, the
complement of `isAccountableDropoff`, from which `JobAcceptReconciliation`'s leftover counter also
derives) leaves the denominator — "can never mint" is the criterion, not "was never touched", so a
flicker-activated identity-less placeholder shrinks too.
Completion unproven keeps the conservative dilution, an **UNASSIGNED** order is never shrunk (the
`owedDropoffs` doctrine — quoted but maybe unpaid), and an `endSession` bail can never prove
completeness: the proof reads only MINT-QUALIFIED evidence, since the bail force-stamps
`completedAt` on an arrived-but-undelivered drop. (2) the #997 **store-correspondence ladder**:
`Task.mintedByOfferHash` (stamped by `JobAcceptFlow.preCreatedDropoffs` at fresh-mint AND add-on
absorption; slot identity, so `swapTaskAccumulation` does not move it) is only a HINT — placeholders activate blind first-open — so the authoritative correspondence is the drop's
reconciled STORE against each accept's own `AcceptedOfferEconomics.storeHints`, matched through
`StoreKeys.normalizedChain` (the #159 SSOT — no second normalizer, no platform literals). Offers and
drops form components by normalized store: one offer in a component → its exact quote over its own
drops (`PER_OFFER_STORE`); N>1 same-store offers → **sub-pool** (`SUB_POOLED_STORE` — which
same-store quote is which drop is platform-side unknowable); a store-less drop joins its mint
stamp's component (`STAMP_FALLBACK`); and at a proven-complete close an offer left with NO drop
because its order **consolidated** onto a sibling's hands its quote to the component whose drops
carry that customer, evidenced from the pickup side as #749 does (`CONSOLIDATED_CUSTOMER`). A
cross-store swap auto-corrects (store beats stamp). (3) the **pooled degrade** (`JOB_POOLED`) —
bit-exactly the old job-wide equal split, and **wholesale**: a single drop placeable on neither
store nor stamp sends the ENTIRE job here (such a drop might belong to any quote; an unassigned
order must keep diluting; degrading to null was rejected — "known Σ, unknown split" IS the
`offerPayShare` semantics). **Σ invariant (structural):** each quote enters at most one component
and splits only across that component's drops, so Σ stamped ≤ Σ accepted quotes at either mint
site; a quote the ladder can place on NO drop stays unattributed and is counted
(`ClosePlan.unattributedOffers`, a one-per-job WARN — the per-drop `eligibleButUnsplit` signal
structurally cannot see a dropless offer). Every degrade logs one PII-safe DEBUG naming its arm.
`DeliveryPayload.offerPayAttributedHash`/`offerPayAttribution` ride the log as the RESOLVED
attribution — the offer a share was actually paid from and which rung resolved it, NOT the raw mint
stamp (`jobOfferHashes` carries the whole add-on CHAIN on every row, so the log had no per-drop
join at all). No fold consumer today (the `jobOfferHashes` precedent), for #756's settlement split
and a per-offer #975. Standing
residuals: history keeps its wrong-but-flagged `OFFER_PAY` shares until #756 (or a driver
`DELIVERY_ADJUSTMENT`), and a mid-stack pay-less PostTask exit still rides unattributed (#691
FIX-1). Miles from `metadata.odometer` partition deltas, time from timestamps. **Economics are FROZEN per
record, never recomputed** (dev decision): each `delivery_record` stores `netProfit` +
`frozenCostPerMile` + its frozen `frozenFuelPerMile`/`frozenNonFuelPerMile` split (#659, the 4-step
true-net waterfall Gross → −Fuel → −Non-fuel → Net; the split rides the SAME frozen
`OfferEvaluation` — `fuelCostEstimate`/`nonFuelCostEstimate` ÷ `distanceMiles`, invariant
`fuel+nonfuel ≈ cpm`; null off an `OFFER_FROZEN` basis → the waterfall falls back to 3-step) +
`costBasis`, computed at projection time against the offer's own frozen
`OfferEvaluation.operatingCostPerMile` (session granularity — the offer→delivery `jobId` link is
absent in the log, but cpm is session-uniform), so editing economy settings only affects **future**
evaluations: a record is an immutable historical fact. Session hydration rehydrates `started` from a
persisted `session_records.startSource` marker (#659), not a "has a real platform" heuristic. Each
`delivery_record` also carries `cashTip` (driver-entered cash — the tip vocabulary's driver-attested
source; kept OUTSIDE `realizedPay`/`netProfit` and added to gross/net only at the read sites, so the
reconciliation's Σ-attributed stays structurally cash-free, #688) and `originalPayBasis` (the
payBasis stamped at FIRST fold, never rewritten by a correction — the #691 receipt-evidence
hydration reads `COALESCE(originalPayBasis, payBasis)` so a re-priced `USER_CORRECTED` row keeps its
original receipt evidence, #703). The v9→v10 migration is additive-only (five nullable columns —
delivery +2, offer +2, session +1); v10→v11 likewise (delivery +2 — `cashTip`/`originalPayBasis`;
`PROJECTOR_VERSION` 3→4 refolds them from the log, populating `originalPayBasis` for all history —
#703's requirement, not a corrections one — and re-stamps `CURRENT_FALLBACK` rows against today's
economy). **Store entity resolution (#159, Room v11→v12 additive):** two tables `stores`
(identity/resolution only — deterministic `storeKey = platform|normalizedChain|runningKey`, D2/F5/F7)
+ `pickup_records` (per-`PICKUP_CONFIRMED` visits; dwell = `confirmedAt−arrivedAt` derived at read),
plus `delivery_records.{storeKey,payoutStoreForms,storeKeyPinned}` +
`offer_records.{storeKey,linkedJobId}`. Recognition is one pure `:domain` resolver core
(`StoreResolver`/`StoreKeys`, shared with the field-verified shadow `StoreChainProjector` via a `Job`
adapter); the projector runs it **resolve-from-rows** inside the batch transaction
(`StoreResolutionRunner`) — a `DELIVERY_COMPLETED` persists the FULL receipt store-form set to
`payoutStoreForms` (B1/B2) and resolution reads that from the committed rows, so a payout-less
`DASH_STOP` re-run recomputes the SAME keys. A driver `newStoreName` correction
sets a sticky `storeKeyPinned` (H1, never re-keyed). Per-store reads group on the resolved key,
unresolved rows fall back to `normalizedChain(storeName)` (F9); the #315 Patterns surface (store
report card + dwell percentiles) is the consumer. `PROJECTOR_VERSION` 4→5 refolds all history and
(F8) the version-bump wipe also clears `stores`/`pickup_records`. **#773 (address running-key
fallback, `PROJECTOR_VERSION` 6→7):** a chain-bare receipt (no parenthetical code) falls back to an
address-derived key — `StoreKeys.addressRunningKey` takes the leading pure-ASCII street-number token
(1–6 digits, fail-null on ranges/suffixes/non-numeric), `@`-prefixed (`@12125`) so provenance is
self-describing; the ladder is receipt key > address(`@`) key > chain-only with tier-aware monotonic
upgrades (an address key never downgrades a receipt key), and `normalizeRunningKey` strips a leading
`@` from receipt-path keys so a payout parenthetical can't masquerade as address-tier.
**#887 (supersession sweep, `PROJECTOR_VERSION` 8→9):** a monotonic key UPGRADE (address-tier →
receipt-tier, or chain-only → keyed) re-keys the visit rows but used to LEAVE the superseded `stores`
identity row behind as a zero-visit phantom. `StoreResolutionRunner` now collects each row's PRIOR
key wherever a re-stamp actually lands (a downgrade-blocked or H1-pinned row keeps its key, so it
contributes nothing) and, STRICTLY LAST in the job — after every pickup/delivery/offer stamp is
committed — deletes each superseded row that `AnalyticsDao.storeKeyReferenceCount` proves is
referenced by ZERO pickup/delivery/offer rows. The guard **fails toward KEEPING** (deleting a row the
re-key didn't reach would orphan a *referencing* row, strictly worse than a phantom) and counts a
merchant-free WARN (P7; keys stay at DEBUG). The sweep also **converges incremental ≡ refold on the
raw `stores` table**: a from-zero refold that sees the receipt evidence in the same batch never mints
the intermediate row, while an incremental fold split across a page boundary mints then deletes it.
**#1000 (pickup-less offer link, `PROJECTOR_VERSION` 9→10):** `StoreResolutionRunner.resolveJob`'s
`pickups.isEmpty()` short-circuit used to discard the job's **exact** offer↔job link along with the
missing store anchor — a blown-through pickup (PICKUP_ARRIVED with no PICKUP_CONFIRMED, the
#615-class fail-null) meant the `DELIVERY_COMPLETED` fold's own carried `offerHashes` never reached
`offer_records.linkedJobId` though the link was already in the log. The runner now stamps
`linkedJobId` for the exact-hash rows via a **link-only** DAO update (`linkOfferToJobIfUnlinked` —
`SET linkedJobId WHERE linkedJobId IS NULL`, never touches `storeKey`, never overwrites an existing
link, never the unverified temporal nominee — no anchor means no brand-token agreement check to run).
`storeKey` stays fail-null by design (an offer-form fallback was REJECTED — a divergent
`normalizedChain` would permanently split the store entity, fail-wrong beats fail-null); the delivery
row's own `storeKey`/`milesToStore`/dwell sample are likewise correct residuals, not bugs.

**#1030 (early_offline fake $0 report, `PROJECTOR_VERSION` 10→11):** the EARLY_OFFLINE `DASH_STOP`
branch stamped `totalEarnings = Session.runningEarnings`, a non-nullable `Double = 0.0` whose only
feeders were the then-dead money parses (#1029), so every summary-less dash wrote a **hard `0.0`**
that `COALESCE(s.reportedEarnings, d.deliveredPay, 0)` honoured as an authoritative "$0 reported",
tripping the over-attribution severe flag on a whole week.
**`RecordFolds.reportedEarningsOf` is the rule's ONE owner:** a `totalEarnings` of `0.0` is a report
only when the end source is the summary screen; on every other source it is the unfilled default.
Four layers. (1) The stamp is `runningEarnings.takeIf { it > 0.0 }`. (2) The fold normalizes,
because history carries the literal `"totalEarnings": 0.0` and a refold would replay the lie —
hence the bump, which re-stamps all 41 affected rows (the drill-down and CSV read the row raw).
(3) `ParsedFields.SessionEndedFields.totalEarnings` became **nullable**, dropping the factory's
`?: 0.0`, since a missed *parse* used to fabricate exactly the `$0` the summary-screen carve-out
trusts as a measurement (`ModeEffects` now stamps null and states the end without a figure;
`PlatformRegionStepper` only moves `runningEarnings` on a real parse). (4) Read-side **mirrors** of
the same source-keyed rule: the DAO's three `reportedEarnings` query families take
`CASE WHEN s.endSource = 'summary_screen' THEN s.reportedEarnings ELSE NULLIF(s.reportedEarnings, 0) END`
on **every** arm — gross AND the `unattributed`/`overAttributed` CASEs, since guarding gross alone
would leave a `0.0` row still flagging `overAttributed = deliveredPay − 0` — and `SessionDetail`
(the per-dash drill-down) applies it via `SessionRecord.endSource`. A blanket `NULLIF` was rejected:
it would silently override a genuine parsed `$0` summary with delivered pay. The bubble HUD's two
`reportedEarnings ?: 0.0` render sites became `EMPTY_VALUE` (#936). Scope:
`session_records.reportedEarnings` only — attribution metadata, so no frozen economy column moves.
`NetProfit`
(`:domain`) is the one shared cost-math SSOT for both the offer estimate and the frozen realized net.
`AnalyticsRepository` (`:core:data`, **DAO-only — no economy dependency**, so historical net is
structurally immutable) serves period economics (`SUM(netProfit)` frozen + `unattributedPay`;
all-pay gross = reported-total authoritative + the unattributed review flag; Monday-week boundaries
via `PeriodBounds`, midnight-reactive) as Room-invalidation Flows to the home glance and the
Analytics hub (#315). **Arbitrary review windows (#970, redesign epic #969 stage 1).** Every period aggregate has a second
overload taking an `AnalyticsWindow` (`:domain` — granularity + half-open span of local **dates**:
`DAY`/`WEEK`/`MONTH`/`LIFETIME`/`CUSTOM`), so the hub's pager/range picker read any span while the
rolling four-value `AnalyticsPeriod` stays as it was for the home glance. **One rule set, two
callers:** `PeriodBounds.of(window, zone)` is the primitive; `PeriodBounds.of(period, now, zone)`
delegates via `AnalyticsPeriod.toWindow(today)`, so the paths can't disagree about where Monday
starts. Each repository pair (`periodEconomics`/`decisionEconomics`/`timeEconomics`/`dailyEarnings`/
`noSessionDeliveries`/`orphanOfferGroups`) shares ONE private `…In(boundsFlow)` core: the enum
overload feeds it the midnight-re-anchoring `periodBoundariesFlow`, the window overload a fixed
`flowOf(bounds)` — a paged window must NOT silently become a different span while on screen (the hub
re-resolves which window is current from `currentLocalDateFlow` one level up). `AnalyticsWindows`
(pure `:domain`) owns the calendar math: Monday weeks, month stepping, the **previous-equivalent
window** the recap delta compares against (null for Lifetime — the UI states that rather than
inventing a comparison), and the `canStepForward` fence. The selection persists in **app prefs** as
`AnalyticsWindowSelection` — `Relative(granularity, offset)` for pageable granularities (so
reopening next week still means *this* week), `Custom(start, endInclusive)` for a driver-drawn range
— decoded fail-closed to the current pay week. **Net per day:** `DailyEarnings.net` rides beside
`gross` (`sessionGrossRows`/`noSessionDailyRows`: same frozen columns + cash + the session's
unattributed remainder), so Σ per-day net equals `PeriodEconomics.netProfit` by construction; EMPTY
axis for a single-day, unbounded, or over-`MAX_DAY_AXIS` (400-day) window.
**Pay mix + platform split (#973, stage 2).** `AnalyticsDao.payMixTotals` sums `basePay`/`tip`/
`cashTip` over the population `deliveryTotals` describes (byte-identical `WHERE`);
`AnalyticsRepository.payMixParts(window|period)` serves `:domain` `PayMixParts`, and pure
`PayMix.of(gross, parts)` composes it with that window's OWN `PeriodEconomics.grossEarnings` at the
read site — gross keeps one owner (the repository's `assemble` fold), so the bar always reconciles
with the headline stating it. `bonuses & other` is the RESIDUE `gross − base − tips − cash`,
**floored at 0**, with `partsExceedGross`/`grossOverflow` recording an over-cent negative rather
than absorbing it. `basePay`/`tip` are stamped only on a job's SOLE drop, so partial coverage is
NORMAL: `deliveriesWithBreakdown`/`deliveries` drive a stated caveat + an "at least N%" tips
insight, and ZERO coverage renders "not recorded" instead of a 100 %-bonuses lie.
`platformEconomics(window|period)` runs the by-platform aggregates ONCE through the same `assemble`
(platforms come from the DATA via `Platform.fromWire`, no fixed list) and
`periodEconomics(window, platform)` DELEGATES into that grouped fold, so a split row and a filtered
read can't be assembled two ways. `DailyEarnings.deliveries` (per-session count off the same
`GROUP BY sessionId` subquery; an orphan row counts as one) drives the tappable day bar.
**Offers tab (#975, stage 3).** *Decisions* became **Offers**; `AnalyticsTab`'s declaration order IS
the on-screen order, and the selection is transient ViewModel state — never persisted, never a nav
argument — so a rename can't strand a stored preference or deep link. (1) `AnalyticsDao.offerOutcomes`
gained a `withEstimate` counter → `DecisionEconomics.declinedWithEstimate` +
`hasDeclinedEstimates`/`declinedEstimatesComplete`, so the declined-value card states its population
and says so when NO decline was priced instead of rendering "$0.00" (§9; `SUM` already skipped a
#936 no-verdict decline, leaving the Σ honest but silently partial). (2) **Estimate vs reality**
(`AnalyticsDao.acceptedOfferRealizedRows` → pure `EstimateVsReality.of`): `offer_records`
**LEFT**-joined to `delivery_records` on `d.jobId = o.linkedJobId`, same session-anchored WHERE +
`outcomeResolved IS NULL` (#810 B2) as `offerOutcomes`, so the join's row count IS the funnel's
`accepted`. The join is deliberately **unfiltered beyond that** — it is the DENOMINATOR the surface
must state — and the inclusion policy lives in the pure factory: drop a null frozen
`estDollarsPerHour` (#936), an unlinked offer, an offer sharing its `linkedJobId` with another
accepted offer (a **stack** can't be split per offer — BOTH dropped, fail-null beats fail-wrong
#745), and a job with null `SUM(netProfit)` or no measured minutes. Both bars are a **mean of
per-offer rates**, one vote per decision, keeping the est side a straight aggregate of the frozen
column rather than a second copy of `estNetPay ÷ estTimeMinutes`; realized rate =
`(Σ netProfit + Σ cashTip) ÷ (Σ realizedMinutes ÷ 60)`. `MIN_CONFIDENT_OFFERS`=5 gates a stated
thin-data caveat, never a hidden card. (3) **The offers list** (`offersBetween(...)` + a
byte-identical-WHERE `offerCount`): paged, `OfferFilter`-filtered, ordered
`decidedAt DESC, eventSequenceId DESC` (the sequence tie-break makes a LIMIT/OFFSET boundary
deterministic across same-millisecond closes), mapped to typed `:domain`
`OfferListing`/`OfferOutcome` — the `AppEventType.name` ↔ chip ↔ pill mapping has ONE owner, no
`"OFFER_DECLINED"` literal anywhere. `AnalyticsRepository` clamps the page to `MAX_OFFER_PAGE`=250
itself (bounded ingestion is the read's job, not the UI's), and the list feed is its own
`OffersFeedState` `StateFlow` beside `uiState` (the `pickerMonth` precedent) so a chip tap doesn't
re-emit every other tile.
**Home = "Today" (#977, stage 4).** A forward-looking glance over the SAME read-model, **zero** new
queries: **So far today** is the rolling `TODAY` `periodEconomics` (replacing the old four-window
`PeriodReview` selector, whose windows live on the #970 pager now); **This week** is
`periodEconomics(week)` + `previous(week)` + `dailyEarnings(week)`, and `Recap →` writes the hub's
*persisted* `AnalyticsWindowSelection.Relative(WEEK, 0)` **before** navigating, so the DataStore
stays the selection's one owner (no nav argument, no Home-local copy). **The plan strip** is the
pure `:domain` `DayPlanner` over today's weekday row of the LIFETIME `earningsHeatmap`: spent hours
dim, the best still-available contiguous 2–4 h run is outlined, and its headline rate is
**coverage-weighted** (`Σ net ÷ Σ coverage`, the heatmap's own division applied to the run) rather
than a mean of cell rates. A cell qualifies only with a real, positive rate — a masked hour is
unknown and a `≤ $0` hour is a measured bad hour, so both END the run. **Thin data is the
load-bearing rule:** the heatmap unions + apportions session spans before it exists, so the dash
COUNT is structurally unrecoverable read-side; "~5 samples" is therefore honestly measured as
`DayPlanner.MIN_SAMPLED_HOURS`=5 **rate-bearing hour buckets** on that weekday, stated in hours, and
below it the card states the count INSTEAD of any rate (§9). Every headline names the weekday it
quotes; the lifetime-scope + not-a-guarantee qualifier lives in the screen's single
`How these numbers work` footer (`weekly_plan_provenance`), not per-card copy (#1024 D2).
Time-derived values follow Reactive-UI rule 2 — state holds the anchors (heatmap + local date), the
composables derive clock/current-hour from `rememberNow()` through a `derivedStateOf`. Two SSOT
moves rode along: `RecapModel` became `:domain` `NetDelta` (a feature module can't reach an `:app`
owner) and `PatternsTab`'s private heat ramp became `:core:designsystem` `AppHeatScale`. Home's
review rows read the hub's action-FREE `reviewTexts` resolver and render the extracted `ReviewList`
(the hub keeps `reviewItems` + `NeedsALookCard`'s container), so Home structurally cannot receive a
per-row action it would have to strip; the action normalises to *navigate* into the hub, which owns
the assign/attest dialogs.
**Heatmap toggle + store leaderboard (#979, stage 5; both renders moved to the Playbook in #1024).**
UI-only over the already-free `storeReportCards()`/`earningsHeatmap()` reads — **zero** new queries,
under an `ALL TIME` chip + declaring caption so the lifetime scope reads as deliberate rather than
as ignoring the #970 pager. The Rate/Hours `AppSegmented` toggle runs over the SAME grid
(`EarningsHeatmapCell.coverageHours` was already computed beside `.dollarsPerHour` and never
rendered); Hours mode reuses `AppHeatScale.cellColor` UNCHANGED (Principle 5 — no second color path)
by folding a genuinely-zero coverage cell to `null` first, landing it on the same "no data" swatch a
masked Rate cell uses, so Hours has no Rate-equivalent "worked, no net" third state and carries its
own legend/caption, while the best-hour $/hr callout stays Rate-mode only. The leaderboard reuses
the existing chain/location-key `StoreLocationChip` (there is no city/neighborhood data anywhere in
the schema); its bar scale (`PatternsModel.maxNet`) and outlier fleet
(`PatternsModel.isWaitOutlier`) are computed over the FULL list regardless of the active sort
(`PatternsModel.sortedStores`), so switching sort chips reorders rows without rescaling or
reflagging them underneath the driver. **Outlier definition (no prior convention to anchor to, so
documented at the constant):** a store's own `p50DwellMillis` is flagged at `>= 1.5×`
(`OUTLIER_MULTIPLIER`) the FLEET's median `p50DwellMillis`, computed across every listed store
carrying a real dwell sample and gated on at least 3 such stores (`MIN_FLEET_SAMPLE_FOR_OUTLIER`,
§9 thin-data honesty); a store with no wait sample of its own is never flagged. "Recent" reuses the
DAO's existing `ORDER BY lastSeenAt DESC` rather than a new query.
**Weekly Plan (#981, stage 6)** — the redesign's NEW surface, and the first thing in this section
that is **not** a projection of the event log. (1) **The read:** the plan ranks weekday×hour cells by
**median** realized net $/hr, which `EarningsHeatmap` structurally cannot answer (its cell rate is
`Σnet ÷ Σcoverage`, a coverage-weighted MEAN with the individual days summed away), so
`AnalyticsRepository.hourOfWeekSamples()` feeds the **same two lifetime DAO reads** (`sessionSpans`
+ `deliveryNets`) to the pure `:domain` `HourOfWeekSampler`, which apportions them one calendar step
earlier — into `(date, hour)` buckets — so a cell's samples ARE its own separate days. No new query,
no schema change; it inherits the heatmap's union / apportionment / completion-hour rules verbatim
(Principle 5), and its coverage floor is the heatmap's own `DEFAULT_MIN_COVERAGE_HOURS` expressed as
a FRACTION (half the range asked about). (2) **The planner** (`WeeklyPlanner`, pure): median ranking,
`MIN_SAMPLE_DAYS`=3 separate days per cell, adjacency merge (greedy tie-break rate → **longest** →
earliest day → earliest start; longest-first is what performs the merge — shortest-first would
render one 4h run as two stapled 2h rows), the ≥2h floor + 4h cap shared with `DayPlanner`, greedy
fill to an hours or `$`-goal target (`MAX_PLAN_HOURS`=40 bounds the dollars arm), `median × hours`
projection, and an overall-median-of-every-worked-hour random baseline. Every weekday holding no
window is returned with a MEASURED reason (`NO_HISTORY`/`THIN_HISTORY`/`NO_CONTIGUOUS_RUN`/
`NO_POSITIVE_RATE`/`TARGET_ALREADY_MET`/`REMOVED_BY_YOU`), never omitted. Driver edits are
**replayed**, not applied: the ViewModel holds an ordered `List<PlanEdit>` and the base plan is
recomputed every emission, so a moved window re-derives its rate at its NEW hours (or prices
nothing, visibly) and a dropped window's range is banned so the re-fill takes the next-best hours.
(3) **Storage + the loop:** `SavedWeeklyPlan` is FROZEN at save time (the driver is graded against
the plan they were shown — the `delivery_records` doctrine applied to a commitment), encoded by the
fail-closed `WeeklyPlanCodec` into the `weekly_plan` DataStore via `WeeklyPlanRepository` (keep the
last 2 weeks). `WeeklyPlanWorker` is a **periodic 7-day** WorkManager job whose initial delay is
computed to the next Sunday 18:00 local by the pure `WeeklyPlanSchedule`, re-anchored on every app
start (which also corrects the 1h DST drift a 7×24h interval accrues); it grades the finished week
with the pure `WeeklyPlanGrader` (planned vs actual hours and dollars **inside the planned windows
only**; money earned outside is reported separately, never folded in) and posts on its **own**
channel — `weekly_plan_channel`, id 104 — deliberately not `app_notice_channel`, because a recurring
engagement nudge must be mutable without also muting the Pledge disclosures. Notification copy is
counts and dollars only (P7). Home's pointer row reads the plan for the week the driver is IN
(`weekStartOf`, not `planWeekStart` — on a Sunday those differ), and "where the plan came from"
renders the SAME `HeatmapGrid` with the picked cells outlined.
**Time tab: the two rates, the typical hour, gap stats (#983, stage 7/7)** — surfaces
`docs/design/running-hourly-rate.md`, the **semantic SSOT** for hourly rates; all read-side.
(1) **The gap fold.** `WorkGaps` (pure `:domain`) pairs each completed drop with the **next accepted
offer in the same dash** and measures the span. Rules, all fail-null: never across dashes or days (a
session-less "(No session)" row therefore contributes nothing — the ONE window aggregate with no
null-session fallback, by definition, not oversight); "which accept came next" is a **`sequenceId`**
question while "how long" is a **timestamp** question (#732); a dash's LAST drop is a **tail, not a
gap**; a pairing whose accept escapes the dash's own effective end
(`COALESCE(endedAt, lastEventAt)`, the `sessionTotals`-shared definition) is incoherent and dropped.
A gap ≥ `LONG_GAP_MILLIS` (2 h) is **counted and stated, never excluded** — the doc names
break-vs-dry-market as unresolvable on-device, so deleting the time would be a worse lie than
reporting it unjudged. **Source: the read model, not `app_events`** —
`delivery_records.completedAt`/`offer_records.decidedAt` ARE those payloads' own domain timestamps
and each row's PK IS its source event's `sequenceId`, so paging the log would re-decode JSON for
identical numbers while re-implementing the session-anchored windowing (Principle 5). The accept
side deliberately KEEPS a resolved orphan (#810 B2) unlike every other offer read: the row is the
instant the driver stopped waiting, and dropping it would fuse two real gaps into one fabricated
long one. (2) **The net/hr pair** (`NetPerHourPair`) is the doc's **active** / **scheduled**
denominators under the driver-facing labels *while working* / *whole shift*: whole-shift = Σ session
durations (zero estimates); while-working = Σ per-delivery partition deltas **minus the measured
gaps** (the delta already swallows the dry wait the doc excludes). Both residuals are one-sided and
documented (a dash's pre-first-offer wait has no preceding completion, so it stays in the
denominator), so while-working is conservative by construction; the numerator is the window's own
FROZEN net, shared with the recap hero. A missing denominator yields **null, never `$0.00/hr`**
(#936 discipline). (3) **"Your typical online hour"** (`HourComposition`) splits the online span
three ways: *at stops* = Σ pickup dwell (`confirmedAt−arrivedAt`) + Σ **door** dwell
(`completedAt−arrivedAt`) — "at stops", not "at store", because a doorstep is not a store; *waiting*
= the gaps (disjoint from dwell by construction — a gap runs completion→accept, a dwell sits inside
a job); *the rest* = the residual, labelled "driving & other" and NEVER as pure driving, since it
also holds the pre-first-offer wait, the post-last-drop tail, and every untimed stop. **Coverage is
a field, not a footnote** (§9): `stopsTimed`/`stops` and `gapCount`/`completionsWithoutGap` ride the
models, an untimed stop is never estimated from its neighbours (so at-stops is a floor), and the
card states all three coverage states separately. The waiting leak is priced at the **while-working**
rate (the whole-shift rate would be circular — it already contains the idleness).
Plumbing: `deliveryTimeTotals` gained door dwell + its coverage counters, new
`pickupDwellTotals`/`completionEvents`/`acceptEvents`/`sessionEndBounds` DAO reads landed, and
`TimeEconomics` gained the dwell/stop fields (+ derived `atStopsMillis`/`stops`/`stopsTimed`).
`Percentiles.nearestRank` is ONE owner for the #159 dwell p50/p95 and the gap median/p90. **A known
seam:** the Weekly Plan's `NOT PICKED` rows would want a **per-weekday, lifetime** gap median, but
these stats are window-scoped — appending one to the other would back a per-weekday claim with an
all-week number (a §9 violation) — so it is left until a grouped lifetime read exists.
**The three destinations (#1024 — the declutter arc; pure re-composition, zero read changes bar one
deletion).** Home = "today", the hub = "the past", the **Playbook** = "the next move".
`AnalyticsTab` lost `Patterns` (Money · Offers · Time) — a pure deletion, the selection being
transient ViewModel state on one argument-less route — and `AnalyticsViewModel` dropped the two
lifetime reads with it, so every source the hub collects is window-anchored. `ui/main/playbook/`
(`Screen.Playbook`, reached from a Home entry tile) holds four sections over **zero new queries**:
this week's plan with live progress; **when you earn** (the `HeatmapGrid` + Rate/Hours toggle, saved
plan's picked cells outlined via `SavedWeeklyPlan.covers`); **where you earn** (the #979
leaderboard, moved verbatim — the app's ONLY store list); and the locked **Demand around you** row,
split out of `GrowthRows` so both screens render ONE owner of that copy. `PatternsModel` +
`HeatmapGrid` + `DisclosureRow` live in `ui/components/` (a shared render whose home is a deleted
surface is the drift Principle 5 punishes), joined by `NetBar` and `HairlineDivider`;
`:feature:dashboard` keeps module-internal copies for Home by the honest-deps doctrine.
**`PlanProgress` (pure `:domain`) is a VIEW of `WeeklyPlanGrade`, not a second grading:** a finished
grade + the clock (today + hour), adding only the elapsed classification. Its `elapsedPlannedHours`
counts a window's hours only once they are OVER (hour granularity, matching `HourOfWeekSamples`'
buckets), so progress is conservative, never flattered — with ONE documented exception: the count is
WALL-CLOCK, so a DST-forward day overcounts the schedule side by an hour (characterization-tested;
correcting it would need a `ZoneId`, i.e. clock-awareness in a pure type, and the worked side is
immune because the sampler apportions real milliseconds). No clock is in the state: the card reads
`rememberNow` ONCE per tick and derives BOTH the local date and the hour from that single instant
through a `derivedStateOf` (Reactive-UI rule 2), ticking once a minute — a flow-supplied date beside
a ticker-supplied hour would skew across midnight and time-zone changes. A decoded plan with NO
windows is treated as no plan at the first consumer, and the screen renders nothing until the first
emission rather than flashing every empty state over real data. Home is four blocks (a `Today` card,
a `This week` card of hairline rows, one row of four entry tiles — Analytics · Playbook · Ratings ·
Settings, the settings FAB and Strategy/Economy tiles retired with the gated permission/first-run
states carrying their own Settings link — and the shared footer); the recap hero is the kept figure,
the delta, and ONE facts line, gross having moved to the money card's headline and acceptance to the
Offers funnel. In the Money tab `PayMixSection` sits INSIDE `MoneyWentCard` (one card, two bars,
headlined `$X came in. $Y went to the car.`; the kept clause is dropped because the hero states it,
**except on a LOSING window**, where it returns in the bad tone, because `AppStackBar` weights on
the value so a negative kept segment is a zero-width sliver and the legend note is silent — without
the clause a window that cost more than it paid would show a green key beside nothing, the #662-F1
anomaly papered over); the card renders for EVERY window with only the chart half hidden on an empty
day axis (a Lifetime/single-day window must not lose its rates); and
`NeedsALookCard`/`RecentDashesCard` keep their content untouched (Home reuses `reviewItems`
verbatim, so the flags/copy SSOT could not move). `TopStoresCard` was DELETED with its **whole dead
chain** — `AnalyticsUiState.topStores`, the ViewModel's collection,
`AnalyticsRepository.perStoreEconomics` (both overloads + core + `chainBucket`),
`AnalyticsDao.deliveryTotalsByStore`/`storeChainDisplays` + their row types, and the `:domain`
`StoreEconomics` chain model — two overlapping store models being the drift Principle 5 names.
`DisclosureRow` gained `HowNumbersWorkFooter`, the shared **one-disclosure-per-screen** row whose
frozen-cost and estimate lines reuse the exact strings their originating cards ship, plus an opt-in
projection line for screens that project (the Playbook opts in; a period-scoped review surface
projects nothing). It renders in **`AnalyticsScreen`, below the tab content** — NOT inside
`MoneyTab`: the recap hero states frozen net ABOVE the tab switch, so a Money-only footer would
leave Offers and Time showing that headline with its qualifier nowhere on screen. **Every §9 state
still states its reason**, in a named place: pay-mix zero coverage / partial coverage /
parts-exceed-gross inside `PayMixSection`, the fuel-split coverage guard inside the merged card's
expanded disclosure, the null-denominator em-dash on each rate row, and the no-predecessor line
(`analytics_hero_delta_none`) on the hero — whose facts-line comparison clause requires a
**non-EMPTY** predecessor (`NetDelta.isEmpty`), since an unworked previous week arrives as a real
zero-filled `PeriodEconomics` and `vs $0.00 the window before` would read as a measurement of a
worked week. The design system gained a first-class **three-way legend note**
(`AppSegment.noteHidden` + the pure `legendNote`, unit-tested in `:core:designsystem`): a stated
figure, a `null` falling back to the computed PERCENTAGE, and an explicit "say nothing here" for a
figure another surface owns (the Money card's kept segment — the `""` sentinel it shipped with first
was one `isNullOrBlank()` tidy-up away from silently rendering a share).
**The "(No session)" bucket (#660 piece 1):** `delivery_records` rows whose source event carried NO
`sessionId` were already counted in net (`deliveryTotals`'s own-`completedAt` fallback, #655) but
invisible to gross (`grossAndUnattributed`/`sessionGrossRows` iterate `session_records` only) — a seam
that could let displayed net exceed gross. Fixed by folding the same null-session population
(`AnalyticsDao.noSessionTotals`/`noSessionTotalsByPlatform`/`noSessionDailyRows`) into
`PeriodEconomics.grossEarnings` and the per-day chart (bucketed on the delivery's own `completedAt`
day, there being no session start to anchor on), and surfacing it as its own
`noSessionPay`/`noSessionDeliveries` review signal (Money tab callout). **Piece 2: categorize an orphan into its real dash.** The correction
event `DELIVERY_SESSION_ASSIGN` (`DeliverySessionAssignPayload{targetEventSequenceId, newSessionId
(null⇒unassign/undo), note}`, folded by `CorrectionFolds.foldDeliverySessionAssign` — #761 split the
correction folds out; `RecordFolds.foldEvent` stays the dispatcher) is written by
`CorrectionRepository.assignDeliverySession` from the tappable Money-tab callout (orphan list +
±48h/same-platform/ended-only session picker, `NoSessionAssignDialogs.kt`) and the drill-down undo.
The projector's `applySessionAssign` re-attributes **attribution ONLY** — `sessionId` + the additive
marker `delivery_records.sessionAssigned` via `row.copy`, every frozen economy column byte-identical
(never re-prices) — behind FIVE fail-closed guards (movable rows only = null-session OR
already-assigned; real ENDED target session, load-bearing for hydration determinism; platform
coherence; cash-bearing-unassign block; missing row), each a counted ids-only-WARN skip. The session
`deliveries` counter rides a **relative** `bumpSessionDeliveries` ±1 (refold-stable). No
`PROJECTOR_VERSION` bump — a new event type can't exist in folded history. Period totals are **read-side only** — they never re-enter the pure state machine (the
dead `CrossPlatformRegion.PeriodTotals` fields were deleted). The free-tier **CSV export** (#319) is a second
read-side consumer: `AnalyticsRepository.buildCsvExport` reads raw `deliveriesBetween`/`sessionsBetween`
rows (row-level, bucketing-free — the driver's own records dumped, not session-anchored periods) and the
pure `CsvExporter` (`:core:data`, RFC-4180 + machine `Csv`/`IrsMileage` primitives in `:domain`) formats
deliveries/sessions/summary CSVs; the SAF directory-write edge is a `:app` ViewModel (Settings → Data &
Privacy → Export Data). Merchant/store names are exported (driver-owned); customer/address hashes are
excluded; no network. **Driver corrections (#650/#688 phase A)** are append-only events written by
`CorrectionRepository` from the per-dash drill-down (`SessionDetailScreen`): `MANUAL_DELIVERY` (a
driver-entered missed drop → `MANUAL`-basis row) and `DELIVERY_ADJUSTMENT` — one **Adjust delivery**
dialog (store/pay/tip/cash-tip/miles/note) writing a single all-optional-fields event. The
orchestrator applies each non-null field by-PK: `payBasis` flips to `USER_CORRECTED` **iff pay
changes**, so a store/tip/cash edit never drops an "est. offer pay" disclosure; a MANUAL row stays
MANUAL; net recomputes only when pay/miles change, against the row's OWN frozen cpm;
`originalPayBasis` is preserved via `row.copy`; `newCompletedAt` is banned outright. Legacy
`PAY_ADJUSTMENT` stays readable for history but new UI never writes it. The projector folds
corrections non-destructively (the original event/row is never deleted) and rebuild-faithfully. **Per-leg mileage (#688 phase B, Room
v12→v13 additive, `PROJECTOR_VERSION` 5→6):** the fold consumes the lifecycle `metadata.odometer`
stamps (PICKUP_ARRIVED closes a to-store leg; DELIVERY_ARRIVED closes a to-dropoff leg keyed by the
drop's own taskId, re-arrivals accumulate; PICKUP_CONFIRMED/DELIVERY_CONFIRMED/DELIVERY_COMPLETED
advance the anchor; null-odometer anchors don't advance, so miles roll forward; per-leg floor at 0)
into `delivery_records.{milesToStore,milesToDropoff}`. A drop's `milesToStore` claims one store leg
of its job (exact store-form match, else FIFO; claim-once — a shared-store sibling gets null, no
fabricated split), and `realizedMiles` becomes the leg SUM only when `milesToDropoff != null`, so
per-drop net redistributes within a stack while session/period/IRS/CSV mileage totals stay
odometer-span-anchored. Pending legs describe not-yet-completed drops, so the accumulator persists
as `session_records.legStateJson` (`LegState`/`LegStateCodec` in `:domain`, fail-closed decode →
legacy delta, `MAX_PENDING` 32 bounded) — that persistence is what keeps incremental ≡ from-zero
refold across the projector's 500-event page boundaries. A driver `newMiles` edit wins `realizedMiles`/net (log-order precedence);
the machine leg columns are provenance and are never rewritten, so leg-sum ≠ realizedMiles IS the
visible edit trail. CSV gains `miles_to_store`/`miles_to_dropoff`. Related: #653/#655/#703.
**Orphan-offer resolution (#810 B2, Room v14→v15 additive `offer_records.outcomeResolved`,
`PROJECTOR_VERSION` 7→8):** an accepted offer whose job produced no matching delivery (surfaced by
the `JOB_ACCEPT_MISMATCH` tripwire) is resolved in two tiers, both write-only to the nullable
`outcomeResolved` column — the original `outcome` is never rewritten (the #688 edit-trail pattern).
**Tier 1 (projector, automatic):** folding a `JOB_ACCEPT_MISMATCH` emits an `OfferReconcileFold` the
orchestrator runs resolve-from-rows in-transaction — the pure `JobAcceptMismatchResolver` joins the
closing job's delivered-drop store evidence (`delivery_records.storeName` + `payoutStoreForms`,
normalized via `StoreKeys`) against each accepted offer's parsed store; EXACTLY one store-unaccounted
offer while all others are accounted → `UNASSIGNED_INFERRED`; any other shape (same-store tie,
multiple/zero unaccounted, no evidence) is INCONCLUSIVE → Tier 2 (fail-null beats fail-wrong, #745). **Tier 2 (driver attestation):** a Money-tab callout
(`orphanOfferGroups`) opens `OrphanOfferAttestDialog`; the driver picks the unassigned offer →
`CorrectionRepository.correctOfferOutcome` appends an `OFFER_OUTCOME_CORRECTION` event →
`CorrectionFolds.foldOfferOutcomeCorrection` → the orchestrator stamps `UNASSIGNED_ATTESTED` (null ⇒
undo), rebuild-faithfully. **Read-side exclusion:**
`AnalyticsDao.offerOutcomes`/`offerScoreOutcomes` gain `outcomeResolved IS NULL`, so a resolved
orphan no longer inflates `accepted`/`received`; the session-level `session_records.offersAccepted`
live counter (bubble ModeCard + CSV) is a DIFFERENT fold, left as-is — a documented residual. The
Tier-1 reconcile reads only delivered rows sequenced BEFORE the mismatch event, and `EffectMap`
emits `JOB_ACCEPT_MISMATCH` AFTER the closing job's final `DELIVERY_COMPLETED`, so the store
evidence is complete by construction and the fold is paging-independent (historical logs carrying
the old mismatch-first order deterministically fall to Tier 2). The only state-machine touch is that
emission ORDER within one close step — no new events, no reducer change. **#1095 widened the
tripwire's SCOPE** (see §3): every close edge is in scope now — including the `endSession` teardown —
attributed to the job's own session, reading mint-qualified evidence, with the single-accept floor
lifted from 2 to 1 on a session-end close. Tier 1/Tier 2 resolution below is unchanged; there are
simply more mismatch events reaching it, and each names the session the job actually lived in.

**#1033 layer 2 (late-expanded receipt → `DELIVERY_RECEIPT_REPRICE`, Room v15→v16 additive
`delivery_records.receiptRepricedAt` + `.driverAdjustedAt`, `PROJECTOR_VERSION` UNCHANGED):** layer 1 widens the window; this
is the other half — when the expansion still lands AFTER the completion was minted off the collapsed
shape, the itemization is real evidence that arrived late, and the drop is re-priced from it instead
of being left on the estimate forever. A machine **Tier-1** correction with the same append-only
posture as the driver ones: the original `DELIVERY_COMPLETED` is never rewritten.
`EffectMap.diffReceiptReprice` emits ONE `DELIVERY_RECEIPT_REPRICE` per delivered drop of the job
(a stacked receipt re-prices every sibling), with shares from LITERALLY the same expression the mint
apportions over — `DropPayApportioner.apportion` across `mintingDropoffTasks(…).describedBy(coverage)`
(the `Task.isAccountableDropoff` denominator, intersected with the receipt's own `ReceiptCoverage`) —
so `Σ dropRealizedPay == parsedPay.total` to the cent holds by construction rather than by two copies
of a rule agreeing; keyed `…:<taskId>:<jobId>:r<repriceRevision>` in `effects_fired`, so a
distinct DECISION always lands (a content hash collided with itself on an X→Y→X sequence and the
third emission was dropped). Decided at THREE points: every itemized receipt FRAME (round 4), the job
close from the receipt `completeActiveJob` is about to clear (round 9), and the terminal teardown
`endSession` (round 10) — a job whose itemized receipt was already on screen when it closed may never
render another frame, and its row can still be un-itemized (its first completion was minted on an
earlier PostTask exit, and the close's re-emission is dropped by the per-taskId completion key); the
two cached-receipt points share ONE implementation (`decideFromCachedReceipt`). It can fire at all
only because of TWO markers: `PlatformRegion.lastClosedJobReceipt` (jobId + `receiptSeenAt` +
`repriceRevision` + `lastDecidedPay`, stamped at the job close and SYNTHESIZED at a terminal
teardown, cleared by `endSession`) and
`JobReceiptAnchors` (the job's FIRST `PostTask` entry, plus `exitedPostTask` — the latch saying the
completion mint has RUN for this job, stamped in the `step` wrapper beside `lastActedFlow` so no
early return in `stepCore`/`updateLifecycle` can skip the acted-flow edge the emitter mints on).
The closed-job marker deliberately does NOT model what the completion ROWS hold — it tried
through two review rounds and both attempts failed toward REFUSING a legitimate correction (mirroring
the mint's exit edge misses its eligibility/final-shape filtering; one task's first completion cannot
describe a multi-drop job). The stepper suppresses only its OWN repeated decision
(`lastDecidedPay`, compared STRUCTURALLY since round 10 — a `hashCode` collided two real receipts),
so a receipt whose itemization the mint already carried emits a redundant
"the receipt says X" event that **`AnalyticsProjector.applyReceiptReprice` resolves to a no-op** by
comparing the row (no rewrite, so no `receiptRepricedAt` churn). "Already priced" is the projector's
question: the stepper cannot know which completions persisted, the projector can just look.
**The decision is a pure STEPPER transition, not an effect diff** (`decideReceiptReprice`, in
`updateSessionFields`' PostTask arm): it must update the marker atomically as it decides, which an
effect diff cannot do — `EffectMap.diffReceiptReprice` only reports the one-step
`pendingReceiptReprice` handoff (cleared at the top of the next step, so a snapshot-restored value
can never re-emit). **Ownership is ONE temporal question — "has any acceptance resolved since this
receipt appeared?"** (`lastAcceptResolvedAt` vs `receiptSeenAt`, two region facts; the accept anchor
is stamped at the `OfferLifecycle` accept-latch resolution and survives BOTH the survivor's expiry
and the mint, since forgetting either is what re-opened the window in review). A receipt carries no
job identity, so every other test tried in review — the announce anchor (which falls back to
`recentTasks.lastOrNull()`), an accepted-since flag, a matching total — leaked; a total in particular
is not identity, and it let a stacked job's $20 receipt redistribute the closed job's drops ($5/$15
over a real $10/$10). **Stated cost (fail-null, #745): the STACKED shape — accept the next offer while
this receipt is up, then expand it LATE — is refused and keeps the `OFFER_PAY` estimate; layer 1's 8 s
window is the path that lands it in time.** **A receipt speaks only for the drops it described when it
was read (#1073, rounds 13–14):** `ReceiptCoverage` (the receipt's SUBJECT + every accountable drop of
its job with completion evidence at that frame) is captured beside the cached receipt, and the mint's
`apportion` denominator, the mint's receipt ATTACH and the re-price's denominator are all intersected
with it — one owner, so they cannot disagree and Σ `dropRealizedPay` == the receipt total holds by
construction; an uncovered sibling folds unpriced (fail-null, one WARN at the close) instead of taking
an older receipt's money. Gating the SHARE alone was not enough: the fold prices any drop carrying a
receipt at the whole `totalPay` (`RECEIPT_TOTAL`), so an uncovered drop folded $20 while the covered
one was re-priced at the same $20. But **completion eligibility is NEVER coverage-gated** — a
delivered, arrived drop always mints, and coverage gates only the attach and the share (round 14
coupled them and could delete a delivered drop's row forever: the exit refused it while it was still
active, so the close-out sweep, which scans `recentTasks`, never saw it either). The **subject** is
one resolver — `receiptSubjectTaskId()`, read by the cache, the "Saved: \$X" announce AND the
PostTask-exit mint — and it is the ACTIVE dropoff (arrival or not: the field renders deliveries with
no arrival frame at all — the 06-16 session runs `dropoff_navigation` → `dropoff_pre_arrival` → the
receipt), else the job's last COMPLETED drop; never a pickup, never a prior job's. **Which drop a PostTask frame completes is
NOT changed by #1073** — that is master's semantics, made explicit and shared. **Stated residual,
`#1081`:** a false `post:task` frame while an un-arrived drop is active makes that drop the subject,
and the exit after it — the same task's navigation, an offer overlay, idle, a dash end, any of them —
completes it and spends its durable key. Pre-existing on master (whose announce picks the same drop),
so the coverage layer neither fixes nor worsens it. Three discriminators were tried and each refuted
by a sequence: arrival evidence (the field delivers with NO arrival frame), a foreign announce id
(inert for a dash's first job), and refusing an exit that resumes the same task (it swallowed a
GENUINE receipt followed by a `dropoff_handoff` re-render, and disagreed with the stepper's own lazy
expiry). The one half #1073 does close is its own: the round-10/11 teardown widening now admits the
announced anchor only when that anchor carries arrival or completion evidence, so a false frame can
never re-price its genuinely delivered siblings DOWN (fail-null, #745).
A cached receipt that names no drop of the job is not evidence about it, so it does not suppress the
#691 estimate either. A `completedAt` timestamp was tried first as the coverage discriminator and
rejected in the same series: it is the LATEST retire arm, so the receipt's own anchor could fall
outside its own receipt. Stated
cost: after a multi-drop job closes, a re-render of ONE drop's own receipt would be split across every
covered drop (DoorDash fields one combined end receipt, so the shape is not known to occur). Effect keys are
`…:<taskId>:<jobId>:r<revision>` off `repriceRevision`, NOT the receipt's content hash: an X→Y→X
itemization sequence hashed back onto its own first key and the durable `effects_fired` idempotency
dropped the third emission, freezing the row at Y. `CorrectionFolds.foldDeliveryReceiptReprice` → a `ReceiptRepriceFold` the projector applies
by **(jobId, taskId)** — the state machine never sees sequence ids — setting `realizedPay`/`tip`/
`basePay` (itemization only on the receipt's sole drop, via the new shared `soleDropOfReceipt` SSOT
`DeliveryFolds` now also reads), flipping `payBasis` → `DROP_SHARE`, recomputing `netProfit` against
the row's OWN frozen cpm, preserving `originalPayBasis`, and stamping `receiptRepricedAt`. Two
fail-closed guards: a missing target row is a counted skip (the mid-stack shape where the mint is
still pending — that mint carries the itemization itself), and a DRIVER-owned row is never
overwritten — `MANUAL`/`USER_CORRECTED`, **or** the new `delivery_records.driverAdjustedAt` (v16,
stamped by any monetary `DELIVERY_ADJUSTMENT`/`PAY_ADJUSTMENT`). That column is load-bearing because a
TIP-ONLY edit deliberately leaves `payBasis` intact (#688 VET F1), so the basis test alone let a later
re-price overwrite the driver's own tip. **Log-order rule:** a LATER driver `DELIVERY_ADJUSTMENT` wins
the pay (the driver is the higher authority on their own money) while the `receiptRepricedAt` trail
survives; an EARLIER one is never superseded. Read side: the drill-down shows "re-priced from the receipt" wherever
`receiptRepricedAt != null` (the never-silent #689/#691 disclosure family). Named residual:
`payoutStoreForms` is NOT back-stamped by a re-price, so a job whose only receipt arrived late keeps
its pre-existing #159 store keys.
