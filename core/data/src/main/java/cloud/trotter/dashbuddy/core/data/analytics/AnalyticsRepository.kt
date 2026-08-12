package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryTimeTotalsRow
import cloud.trotter.dashbuddy.core.database.analytics.OfferRecordEntity
import cloud.trotter.dashbuddy.core.database.analytics.OutcomeCountRow
import cloud.trotter.dashbuddy.core.database.analytics.PickupDwellTotalsRow
import cloud.trotter.dashbuddy.core.database.analytics.ScoreOutcomeRow
import cloud.trotter.dashbuddy.core.database.analytics.SessionEventRow
import cloud.trotter.dashbuddy.core.database.analytics.SessionTotalsRow
import cloud.trotter.dashbuddy.core.database.event.AppEventDao
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsWindow
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.DeliveryNet
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmapCalculator
import cloud.trotter.dashbuddy.domain.analytics.EstimateVsReality
import cloud.trotter.dashbuddy.domain.analytics.GapStats
import cloud.trotter.dashbuddy.domain.analytics.HourOfWeekSampler
import cloud.trotter.dashbuddy.domain.analytics.HourOfWeekSamples
import cloud.trotter.dashbuddy.domain.analytics.OfferFilter
import cloud.trotter.dashbuddy.domain.analytics.OfferListing
import cloud.trotter.dashbuddy.domain.analytics.OfferOutcome
import cloud.trotter.dashbuddy.domain.analytics.PayMixParts
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.Percentiles
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import cloud.trotter.dashbuddy.domain.analytics.PlatformEconomics
import cloud.trotter.dashbuddy.domain.analytics.SessionDetail
import cloud.trotter.dashbuddy.domain.analytics.SessionEvent
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionSpan
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import cloud.trotter.dashbuddy.domain.analytics.WorkGaps
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferCandidate
import cloud.trotter.dashbuddy.domain.analytics.OrphanOfferGroup
import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.model.event.AppEventCodec
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.JobAcceptMismatchPayload
import cloud.trotter.dashbuddy.domain.state.Platform
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-side repository over the durable analytics tables (#314 PR3) — the home
 * glance and (later) the hub read period economics from here, never from the state
 * machine (totals are history; a pure stepper must not read the DB — Principle 1).
 *
 * **Net is the frozen stored value.** Period net = Σ per-delivery `netProfit`
 * (frozen by the projector against each accepted offer's cost basis) + unattributed
 * pay. There is deliberately **no economy dependency**: editing the operating-cost
 * model must not retroactively rewrite a past period's net (Principle 5 — one owner
 * for the number, and the owner is the frozen record). The `NetProfit` SSOT is used
 * only for the null-safe rate math ($/hr, $/mi), not to recompute net.
 *
 * **Reactive by construction:** every query is a `Flow`, so Room's invalidation
 * tracker re-emits on each projector commit; the boundary flow re-anchors at local
 * midnight / Monday so "today"/"this week" flip without an app restart.
 *
 * Privacy: economics/counts/store names only — customer PII is already sha256'd
 * upstream and never surfaces here; no new capture, network, or PII surface.
 */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AnalyticsRepository @Inject constructor(
    private val analyticsDao: AnalyticsDao,
    // #810 B2: the Tier-2 orphan-attestation surface reads the `JOB_ACCEPT_MISMATCH` events (the
    // source-of-truth log) to enumerate a job's accepted offers — a DAO read, no economy dependency,
    // so the "net is the frozen stored value" posture above is preserved.
    private val appEventDao: AppEventDao,
) {

    /**
     * Frozen-net economics for [period]. `platform == null` ⇒ cross-platform; a
     * specific [platform] filters to that platform's records. Re-emits on new
     * records (Room invalidation) and at midnight/week rollover (boundary flow).
     *
     * **Session-anchored (#655):** a period contains the sessions that *started* in it, and every
     * figure — delivered pay, frozen net, deliveries, miles, gross, unattributed — derives from that
     * one session set. A midnight-spanning dash lands wholly on its start day, so a dash begun at
     * 11:50pm counts entirely on that evening (its post-midnight deliveries with it), never split
     * across two days. The single bucketing owner is the DAO join (Principle 5).
     */
    fun periodEconomics(period: AnalyticsPeriod, platform: Platform? = null): Flow<PeriodEconomics> =
        economicsIn(periodBoundariesFlow(period), platform)

    /**
     * Frozen-net economics for an **arbitrary** [window] (#970 / brief §7.1) — the period pager's
     * read. Byte-identical accounting to the [AnalyticsPeriod] overload above: both resolve to a
     * `[start, end)` bounds pair and run the SAME [economicsIn] fold, so the plumbing has one owner
     * and the enum path is a special case of this one (Principle 5).
     *
     * Unlike the rolling-enum path there is **no midnight re-anchor flow**: a paged window is an
     * explicit span ("Jul 13–19"), and it must not silently become a different span while the driver
     * is reading it. The hub re-resolves *which* window is current from `currentLocalDateFlow`
     * instead, one level up — the anchor lives with the selection, not with the query.
     */
    fun periodEconomics(
        window: AnalyticsWindow,
        platform: Platform? = null,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<PeriodEconomics> = economicsIn(flowOf(PeriodBounds.of(window, zone)), platform)

    private fun economicsIn(
        bounds: Flow<PeriodBounds.Bounds>,
        platform: Platform?,
    ): Flow<PeriodEconomics> =
        if (platform != null) {
            // ONE owner for the per-platform fold (Principle 5): the single-platform read is a lookup
            // into the same grouped assembly the split rows are built from, so a platform's row on the
            // split card and its filtered economics can never be assembled two different ways. A
            // platform with no rows in the window is genuinely empty, not a fabricated zero-rate.
            platformEconomicsIn(bounds).map { rows ->
                rows.find { it.platform == platform }?.economics ?: PeriodEconomics.EMPTY
            }
        } else {
            bounds.flatMapLatest { (start, end) ->
                combine(
                    analyticsDao.deliveryTotals(start, end),
                    analyticsDao.sessionTotals(start, end),
                    analyticsDao.grossAndUnattributed(start, end),
                    analyticsDao.noSessionTotals(start, end),
                ) { d, s, g, ns ->
                    assemble(
                        deliveredPay = d.pay, deliveryNet = d.net, deliveries = d.deliveries,
                        jobs = d.jobs, miles = s.miles, onlineMillis = s.onlineMillis,
                        gross = g.gross, unattributed = g.unattributed, overAttributed = g.overAttributed,
                        fuelCost = d.fuelCost, nonFuelCost = d.nonFuelCost, cash = d.cash,
                        noSessionPay = ns.pay + ns.cash, noSessionDeliveries = ns.deliveries,
                    )
                }
            }
        }

    /**
     * The window's economics **split per platform** (#973 / brief §4.2 — "DoorDash vs Uber Eats rows")
     * — one entry per platform that has any record in the window, highest gross first.
     *
     * Platform-agnostic by construction (Principle 8): the platforms come from the DATA (each grouped
     * row's `platform` wire, resolved through the [Platform] registry's [Platform.fromWire]), never
     * from a hardcoded list or a `when` over platform literals — so a third platform's rows appear on
     * the card the day its ruleset lands, with no edit here. A wire the registry does not know is
     * dropped rather than guessed at (fail-null beats fail-wrong).
     *
     * Reads the four by-platform DAO aggregates ONCE for the whole split, instead of re-querying per
     * platform, and folds each through the same [assemble] as every other economics read.
     */
    fun platformEconomics(
        window: AnalyticsWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<PlatformEconomics>> = platformEconomicsIn(flowOf(PeriodBounds.of(window, zone)))

    /** Rolling-period variant of [platformEconomics] (the home-glance enum path). */
    fun platformEconomics(period: AnalyticsPeriod): Flow<List<PlatformEconomics>> =
        platformEconomicsIn(periodBoundariesFlow(period))

    private fun platformEconomicsIn(bounds: Flow<PeriodBounds.Bounds>): Flow<List<PlatformEconomics>> =
        bounds.flatMapLatest { (start, end) ->
            combine(
                analyticsDao.deliveryTotalsByPlatform(start, end),
                analyticsDao.sessionTotalsByPlatform(start, end),
                analyticsDao.grossAndUnattributedByPlatform(start, end),
                analyticsDao.noSessionTotalsByPlatform(start, end),
            ) { dl, sl, gl, nsl ->
                // The union of every wire any of the four aggregates saw — a platform can legitimately
                // appear in one and not another (a dash with no completed delivery has session rows
                // only; a null-session orphan has no session row at all).
                val wires = (
                    dl.map { it.platform } + sl.map { it.platform } +
                        gl.map { it.platform } + nsl.map { it.platform }
                    ).distinct()
                wires.mapNotNull { wire ->
                    val platform = Platform.fromWire(wire) ?: return@mapNotNull null
                    val d = dl.find { it.platform == wire }
                    val s = sl.find { it.platform == wire }
                    val g = gl.find { it.platform == wire }
                    val ns = nsl.find { it.platform == wire }
                    PlatformEconomics(
                        platform = platform,
                        economics = assemble(
                            deliveredPay = d?.pay ?: 0.0, deliveryNet = d?.net ?: 0.0,
                            deliveries = d?.deliveries ?: 0, jobs = d?.jobs ?: 0,
                            miles = s?.miles ?: 0.0, onlineMillis = s?.onlineMillis ?: 0L,
                            gross = g?.gross ?: 0.0, unattributed = g?.unattributed ?: 0.0,
                            overAttributed = g?.overAttributed ?: 0.0,
                            fuelCost = d?.fuelCost, nonFuelCost = d?.nonFuelCost, cash = d?.cash ?: 0.0,
                            noSessionPay = (ns?.pay ?: 0.0) + (ns?.cash ?: 0.0),
                            noSessionDeliveries = ns?.deliveries ?: 0,
                        ),
                    )
                }.sortedByDescending { it.economics.grossEarnings }
            }
        }

    /**
     * The window's **pay-mix parts** (#973 / brief §7.6) — Σ base pay / Σ platform tip / Σ cash tip
     * plus the itemization-coverage counters, over the same session-anchored delivery population as
     * [periodEconomics].
     *
     * Deliberately returns the PARTS, not a finished mix: gross has one owner ([assemble]), and the
     * pure `:domain` [cloud.trotter.dashbuddy.domain.analytics.PayMix.of] composes the two at the read
     * site. That keeps the "bonuses & other = gross − base − tips − cash" residue rule in one testable
     * place instead of half here and half in the UI.
     */
    fun payMixParts(
        window: AnalyticsWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<PayMixParts> = payMixPartsIn(flowOf(PeriodBounds.of(window, zone)))

    /** Rolling-period variant of [payMixParts] (the home-glance enum path). */
    fun payMixParts(period: AnalyticsPeriod): Flow<PayMixParts> =
        payMixPartsIn(periodBoundariesFlow(period))

    private fun payMixPartsIn(bounds: Flow<PeriodBounds.Bounds>): Flow<PayMixParts> =
        bounds.flatMapLatest { (start, end) ->
            analyticsDao.payMixTotals(start, end).map { row ->
                PayMixParts(
                    basePay = row.basePay,
                    tips = row.tips,
                    cashTips = row.cashTips,
                    deliveries = row.deliveries,
                    deliveriesWithBreakdown = row.withBreakdown,
                )
            }
        }

    /*
     * `perStoreEconomics` (both overloads, the shared `…In` core and its `chainBucket` helper) lived
     * here until #1024 part 2. Its only consumer was the Money tab's `TopStoresCard`, deleted in B5
     * because the Playbook's store leaderboard — [storeReportCards], per-LOCATION and lifetime — is
     * the app's single store list now. A read with no reader is not inert: it is a live Room
     * observer the hub re-subscribed on every page of the window pager, plus a second, differently
     * shaped store rollup for a future surface to accidentally adopt.
     */

    /**
     * The store report cards (#159, the #315 Patterns tab) — one per **referenced** resolved store
     * entity (M4, no phantom orphan rows), lifetime-scoped, newest-visited first. Combines the DAO
     * rollup ([AnalyticsDao.storeReportRows] — metadata + pickup/delivery counts + gross/net) with the
     * dwell samples ([AnalyticsDao.storeDwellSamples]); avg/p50/p95 dwell are computed HERE (SQLite has
     * no native percentile; store visit counts are small). A chain-only row surfaces as
     * `locationKnown = false` ("location unknown", F6). Reactive by construction (both sources are
     * Room-invalidation Flows). No economy dependency (net is the frozen stored value); no new PII
     * surface (merchant metadata + counts only).
     */
    fun storeReportCards(): Flow<List<StoreReportCard>> =
        combine(
            analyticsDao.storeReportRows(),
            analyticsDao.storeDwellSamples(),
        ) { rows, dwells ->
            val dwellsByKey = dwells.groupBy { it.storeKey }
            rows.map { r ->
                val sorted = dwellsByKey[r.storeKey].orEmpty().map { it.dwellMillis }.sorted()
                StoreReportCard(
                    storeKey = r.storeKey,
                    platform = r.platform,
                    normalizedChain = r.normalizedChain,
                    chainDisplay = r.chainDisplay,
                    runningKey = r.runningKey,
                    address = r.address,
                    locationKnown = r.runningKey != null,
                    pickups = r.pickups,
                    deliveries = r.deliveries,
                    gross = r.gross,
                    net = r.net,
                    avgDwellMillis = sorted.takeIf { it.isNotEmpty() }?.average(),
                    // Nearest-rank, shared with the #983 gap percentiles (Principle 5 — one
                    // percentile convention, two readers).
                    p50DwellMillis = Percentiles.nearestRank(sorted, 0.50),
                    p95DwellMillis = Percentiles.nearestRank(sorted, 0.95),
                    firstSeenAt = r.firstSeenAt,
                    lastSeenAt = r.lastSeenAt,
                )
            }
        }

    /**
     * The driver's own realized net **$/hr by hour-of-week** (#159/#315 H5, Patterns heatmap) — a 7×24
     * grid of *their own* earning experience, never a platform-pay claim. Combines the two lifetime DAO
     * reads: session spans ([AnalyticsDao.sessionSpans], the coverage denominator) and delivery nets
     * ([AnalyticsDao.deliveryNets], the numerator). The apportionment math is the pure `:domain`
     * [EarningsHeatmapCalculator] (span → fractional hour cells, net → the cell of its `completedAt`,
     * rate = Σnet ÷ Σhours with a coverage mask) — this repository stays DAO-only and holds no second
     * copy of that math (Principle 5).
     *
     * **Lifetime-scoped** (no period): the tab is rate-based and hides the period. **Timezone:**
     * [zoneProvider] is evaluated **per emission** inside the combine (not captured once at flow
     * construction), so a timezone move re-buckets history on the very next projector commit — the
     * device zone by default; a fixed-zone supplier is injected in tests. The apportionment + union is
     * off-main ([Dispatchers.Default]) since it iterates the 168-cell grid; [distinctUntilChanged]
     * suppresses re-emitting an identical grid on an unrelated Room invalidation. No economy dependency
     * (net is the frozen stored value); no new PII surface (aggregate net + time only).
     */
    fun earningsHeatmap(zoneProvider: () -> ZoneId = { ZoneId.systemDefault() }): Flow<EarningsHeatmap> =
        combine(
            analyticsDao.sessionSpans(),
            analyticsDao.deliveryNets(),
        ) { spans, nets ->
            EarningsHeatmapCalculator.compute(
                spans = spans.map { SessionSpan(it.startMillis, it.endMillis) },
                deliveries = nets.map { DeliveryNet(it.completedAt, it.netDollars) },
                zone = zoneProvider(),
            )
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    /**
     * The same lifetime record as [earningsHeatmap], decomposed **per occurrence** — the distribution
     * the Weekly Plan takes its medians and sample counts over (#981 / brief §7.7).
     *
     * This is the whole of §7.7's read-side plumbing, and it deliberately adds **no query**: brief §8
     * ranks weekday×hour cells by MEDIAN net $/hr, which the heatmap grid structurally cannot answer
     * (it is a totals grid whose cell rate is `Σnet ÷ Σcoverage`, a coverage-weighted mean, with the
     * individual days summed away). [HourOfWeekSampler] consumes the identical two DAO reads one
     * calendar step earlier — into `(date, hour)` buckets — so a cell's samples are its own separate
     * days and both the median and "over N Fridays" fall out. No schema change, no
     * `PROJECTOR_VERSION` bump, no economy dependency (net is the frozen stored value).
     *
     * Same shape as [earningsHeatmap] in every other respect: lifetime-scoped, [zoneProvider]
     * evaluated per emission so a timezone move re-buckets on the next commit, apportionment off-main.
     */
    fun hourOfWeekSamples(zoneProvider: () -> ZoneId = { ZoneId.systemDefault() }): Flow<HourOfWeekSamples> =
        combine(
            analyticsDao.sessionSpans(),
            analyticsDao.deliveryNets(),
        ) { spans, nets ->
            HourOfWeekSampler.compute(
                spans = spans.map { SessionSpan(it.startMillis, it.endMillis) },
                deliveries = nets.map { DeliveryNet(it.completedAt, it.netDollars) },
                zone = zoneProvider(),
            )
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    /**
     * Offer-decision economics for [period] (#315 H3, Decisions tab): the funnel counts, the frozen
     * est-net of declines ("value of saying no"), and the per-outcome avg score / est-$/hr. Two
     * session-anchored DAO aggregates ([AnalyticsDao.offerOutcomes] + [AnalyticsDao.offerScoreOutcomes])
     * combined and folded into one [DecisionEconomics].
     *
     * **Session-anchored (#655):** an offer belongs to the period its *session* started in (same
     * bucketing owner as every other aggregate — the DAO join), with a null-session fallback on the
     * offer's own `decidedAt`. Re-emits on new offer records (Room invalidation) and at
     * midnight/week/month rollover (the boundary flow).
     *
     * **Estimates are frozen decision-time snapshots, not realized net** — the numbers say what the
     * verdict projected at decision time; an economy edit never re-costs them (Principle 5). No new
     * PII surface: counts + frozen economics only.
     */
    fun decisionEconomics(period: AnalyticsPeriod): Flow<DecisionEconomics> =
        decisionEconomicsIn(periodBoundariesFlow(period))

    /** Arbitrary-window variant of [decisionEconomics] (#970 §7.1) — the pager's offer aggregates. */
    fun decisionEconomics(
        window: AnalyticsWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<DecisionEconomics> = decisionEconomicsIn(flowOf(PeriodBounds.of(window, zone)))

    private fun decisionEconomicsIn(bounds: Flow<PeriodBounds.Bounds>): Flow<DecisionEconomics> =
        bounds.flatMapLatest { (start, end) ->
            combine(
                analyticsDao.offerOutcomes(start, end),
                analyticsDao.offerScoreOutcomes(start, end),
            ) { outcomes, scores -> assembleDecisions(outcomes, scores) }
        }

    /**
     * **Estimate vs reality** for [period] (#975 / brief §7.5, the Offers tab): what the evaluator
     * projected per hour for the offers the driver ACCEPTED, next to what those jobs actually paid
     * per realized hour, plus the plain ratio between them.
     *
     * The DAO read supplies every accepted, non-orphan-resolved offer of the window joined to its
     * linked job's deliveries; the pure [EstimateVsReality.of] owns the entire population policy —
     * the #936 null-estimate exclusion, the unlinked/unmeasured exclusions, and the stacked-job rule
     * (a job claimed by two accepted offers is dropped from BOTH rather than counted twice: fail-null
     * beats fail-wrong, #745). Its KDoc is the authoritative statement of that population.
     *
     * The estimate side is FROZEN decision-time economics and the realized side is the FROZEN
     * per-delivery net — nothing is recomputed here, and there is no economy dependency (Principle 5).
     * No new PII surface: rates and counts only.
     */
    fun estimateVsReality(period: AnalyticsPeriod): Flow<EstimateVsReality> =
        estimateVsRealityIn(periodBoundariesFlow(period))

    /** Arbitrary-window variant of [estimateVsReality] (#970 §7.1) — the pager's est-vs-realized read. */
    fun estimateVsReality(
        window: AnalyticsWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<EstimateVsReality> = estimateVsRealityIn(flowOf(PeriodBounds.of(window, zone)))

    private fun estimateVsRealityIn(bounds: Flow<PeriodBounds.Bounds>): Flow<EstimateVsReality> =
        bounds.flatMapLatest { (start, end) ->
            analyticsDao
                .acceptedOfferRealizedRows(start, end, OfferOutcome.ACCEPTED.eventTypeName)
                .map { rows -> EstimateVsReality.of(rows.map { it.toSample() }) }
        }

    /**
     * One page of the window's offers, newest decision first (#975 / brief §7.4) — the Offers tab's
     * list. [filter] `ALL` drops the outcome predicate entirely; the other three pin it to that
     * outcome's stored `AppEventType` name (the [OfferOutcome] mapping is the one owner, Principle 8).
     *
     * **Paging is a bounded `LIMIT`/`OFFSET` window, not a Paging3 source.** The hub's list is a
     * review surface over one selected period — tens to low hundreds of rows — so a growing page size
     * over one Room-invalidation Flow keeps the whole list reactive (a projector commit re-emits the
     * visible page) with none of the paging-library machinery. [limit] is CLAMPED to [MAX_OFFER_PAGE]
     * here rather than trusted from the caller: an unbounded page is a bounded-ingestion hazard the
     * read side is responsible for, not the UI (Principle 6).
     *
     * **Window-only, deliberately.** Every other aggregate here ships an [AnalyticsPeriod] overload
     * beside its window one because the home glance reads the rolling enum; a per-offer LIST has no
     * glance consumer and never will (it is a review surface), so a second overload would be dead
     * code with a `…In(bounds)` core to keep alive. The private core stays, so adding the enum path
     * later is one delegating line.
     *
     * Privacy: `offer_records` carries no customer fields; merchant names are driver-owned display
     * data (Principle 6).
     */
    fun offers(
        window: AnalyticsWindow,
        filter: OfferFilter = OfferFilter.ALL,
        limit: Int = DEFAULT_OFFER_PAGE,
        offset: Int = 0,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<OfferListing>> =
        offersIn(flowOf(PeriodBounds.of(window, zone)), filter, limit, offset)

    private fun offersIn(
        bounds: Flow<PeriodBounds.Bounds>,
        filter: OfferFilter,
        limit: Int,
        offset: Int,
    ): Flow<List<OfferListing>> =
        bounds.flatMapLatest { (start, end) ->
            analyticsDao
                .offersBetween(
                    start = start,
                    end = end,
                    outcome = filter.outcome?.eventTypeName,
                    limit = limit.coerceIn(0, MAX_OFFER_PAGE),
                    offset = offset.coerceAtLeast(0),
                )
                .map { rows -> rows.map { it.toDomain() } }
        }

    /**
     * How many offers the window holds under [filter] — the `See all N offers` denominator (#975).
     * The DAO's `WHERE` is byte-identical to [offers]' minus the paging clause, so the footer count
     * can never describe a different population than the rows it sits under (Principle 5).
     */
    fun offerCount(
        window: AnalyticsWindow,
        filter: OfferFilter = OfferFilter.ALL,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<Int> = offerCountIn(flowOf(PeriodBounds.of(window, zone)), filter)

    private fun offerCountIn(bounds: Flow<PeriodBounds.Bounds>, filter: OfferFilter): Flow<Int> =
        bounds.flatMapLatest { (start, end) ->
            analyticsDao.offerCount(start, end, filter.outcome?.eventTypeName)
        }

    /**
     * Time / mileage economics for [period] (#315 H4, Time tab): the online-time split, the deadhead
     * (unattributed-miles) ratio, the on-time rate + avg deadline margin, and the session odometer
     * miles behind the mileage-&-tax card. Combines the session totals ([AnalyticsDao.sessionTotals]
     * — online time, miles, dash count) with the delivery-time aggregate
     * ([AnalyticsDao.deliveryTimeTotals] — realized minutes/miles, on-time counts) into one
     * [TimeEconomics].
     *
     * **Session-anchored (#655):** both aggregates derive from the sessions that *started* in the
     * period (the delivery join has the same WHERE shape as [deliveryTotals], with a null-session
     * `completedAt` fallback), so the split and its remainder are internally consistent by
     * construction. Re-emits on new records (Room invalidation) and at midnight/week/month rollover
     * (the boundary flow).
     *
     * **All figures are MEASURED, not estimated** — Σ session durations, Σ per-delivery partition
     * deltas, Σ odometer deltas — with **no economy dependency** (Principle 5: this surface reports
     * measured time/miles, never a re-costed value). Deadline coverage is explicit: the on-time rate
     * covers only deliveries that carried a captured deadline. No new PII surface (counts + measured
     * time/miles only).
     */
    fun timeEconomics(period: AnalyticsPeriod): Flow<TimeEconomics> =
        timeEconomicsIn(periodBoundariesFlow(period))

    /** Arbitrary-window variant of [timeEconomics] (#970 §7.1) — the pager's time/deadhead stats. */
    fun timeEconomics(
        window: AnalyticsWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<TimeEconomics> = timeEconomicsIn(flowOf(PeriodBounds.of(window, zone)))

    private fun timeEconomicsIn(bounds: Flow<PeriodBounds.Bounds>): Flow<TimeEconomics> =
        bounds.flatMapLatest { (start, end) ->
            combine(
                analyticsDao.sessionTotals(start, end),
                analyticsDao.deliveryTimeTotals(start, end),
                analyticsDao.pickupDwellTotals(start, end),
            ) { s, d, p -> assembleTime(s, d, p) }
        }

    /**
     * The window's **between-job gaps** (#983 / brief §7.8) — how long the driver sat between finishing
     * a drop and taking the next offer. Feeds the Time tab's gap card, the "typical online hour"
     * waiting segment, and the while-working denominator of the net/hr pair.
     *
     * **Read-model, not the event log.** The brief points at `app_events`; the two facts the fold needs
     * are already projected — `delivery_records.completedAt` and `offer_records.decidedAt` ARE those
     * payloads' own domain timestamps, and each row's PK IS its source event's `sequenceId`. Paging the
     * log would re-decode JSON for the same two numbers and re-implement the session-anchored windowing
     * every other aggregate here shares, so the read model is both cheaper and the SSOT (Principle 5).
     * The #732 ordering contract is honoured either way: the DAO orders by `sequenceId` and the pure
     * [WorkGaps] fold measures durations from the domain timestamps.
     *
     * The pairing/clamping policy lives entirely in the pure `:domain` [WorkGaps] (never across dashes,
     * the tail is not a gap, bounded by the dash's own effective end) — this repository only supplies
     * rows. Off-main ([Dispatchers.Default]) like the heatmap folds, since a Lifetime window's row set
     * is unbounded by construction; [distinctUntilChanged] suppresses re-emitting identical stats on an
     * unrelated Room invalidation.
     *
     * No economy dependency, no new PII surface: timestamps, ids and counts only.
     */
    fun gapStats(
        window: AnalyticsWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<GapStats> = gapStatsIn(flowOf(PeriodBounds.of(window, zone)))

    /** Rolling-period variant of [gapStats] (the enum path, for parity with every other aggregate). */
    fun gapStats(period: AnalyticsPeriod): Flow<GapStats> = gapStatsIn(periodBoundariesFlow(period))

    private fun gapStatsIn(bounds: Flow<PeriodBounds.Bounds>): Flow<GapStats> =
        bounds.flatMapLatest { (start, end) ->
            combine(
                analyticsDao.completionEvents(start, end),
                analyticsDao.acceptEvents(start, end, OfferOutcome.ACCEPTED.eventTypeName),
                analyticsDao.sessionEndBounds(start, end),
            ) { completions, accepts, ends ->
                WorkGaps.compute(
                    completions = completions.map { it.toDomain() },
                    accepts = accepts.map { it.toDomain() },
                    sessionEndMillis = ends.associate { it.sessionId to it.endMillis },
                )
            }
        }.flowOn(Dispatchers.Default).distinctUntilChanged()

    private fun SessionEventRow.toDomain() =
        SessionEvent(sessionId = sessionId, sequenceId = sequenceId, timestamp = timestamp)

    /**
     * Per-day earnings for [period] (#315 H6, Money-tab chart) — a **complete day axis**: one
     * [DailyEarnings] per local calendar day of the window, in order, with gap days present at `0.0`
     * gross (a driver who skipped Tuesday still gets a Tuesday bar). Gross per day = Σ of the
     * reported-authoritative per-session gross ([AnalyticsDao.sessionGrossRows], the same definition as
     * [grossAndUnattributed]) for the sessions that *started* that day, **plus** any "(No session)"
     * bucket pay ([AnalyticsDao.noSessionDailyRows], #660) whose own `completedAt` falls on that day —
     * the #675 review's flagged follow-up site: a null-session delivery has no session start to anchor
     * on, so it buckets by its own completion day instead (mirrors [periodEconomics]'s null-session fold).
     *
     * **Session-anchored (#655):** a session's whole gross lands on its start instant's local day, so a
     * midnight-spanning dash counts entirely on its start day — never split across two bars. [zone] is
     * injectable so a fixed-zone test can pin the day boundaries; production uses the device zone.
     *
     * [TODAY][AnalyticsPeriod.TODAY] and [LIFETIME][AnalyticsPeriod.LIFETIME] return an empty list: a
     * one-bar chart adds nothing, and LIFETIME's window is unbounded (its days can't be enumerated). The
     * UI hides the card on an empty list.
     *
     * Re-emits on new session/delivery records (Room invalidation) and at week/month rollover (the
     * boundary flow).
     */
    fun dailyEarnings(period: AnalyticsPeriod, zone: ZoneId = ZoneId.systemDefault()): Flow<List<DailyEarnings>> {
        if (period == AnalyticsPeriod.TODAY || period == AnalyticsPeriod.LIFETIME) return flowOf(emptyList())
        return dailyEarningsIn(periodBoundariesFlow(period, zone), zone)
    }

    /**
     * Arbitrary-window variant of [dailyEarnings] (#970 §7.1) — the per-day axis behind the Money
     * chart AND the recap hero's sparkline, for any paged or custom span.
     *
     * Same complete gap-filled axis and same session-anchored bucketing; each entry now also carries
     * the day's **frozen net** ([DailyEarnings.net], §7.3) so the sparkline can plot kept money rather
     * than gross.
     *
     * Returns an EMPTY list — the UI hides the chart/sparkline — when the axis would carry no
     * information: an unbounded (LIFETIME) window whose days can't be enumerated, a single-day window
     * (one bar says nothing the hero doesn't), or a span longer than [MAX_DAY_AXIS] days (a bounded
     * ingestion guard: a multi-year custom range would build thousands of unreadable bars).
     */
    fun dailyEarnings(
        window: AnalyticsWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<DailyEarnings>> {
        val length = window.lengthDays ?: return flowOf(emptyList())
        if (length <= 1L || length > MAX_DAY_AXIS) return flowOf(emptyList())
        return dailyEarningsIn(flowOf(PeriodBounds.of(window, zone)), zone)
    }

    /** The shared gap-filled per-day fold behind both [dailyEarnings] overloads (#970 §7.1/§7.3). */
    private fun dailyEarningsIn(
        bounds: Flow<PeriodBounds.Bounds>,
        zone: ZoneId,
    ): Flow<List<DailyEarnings>> =
        bounds.flatMapLatest { (start, end) ->
            combine(
                analyticsDao.sessionGrossRows(start, end),
                analyticsDao.noSessionDailyRows(start, end),
            ) { rows, noSessionRows ->
                // The end bound is exactly the window's exclusive local midnight (PeriodBounds), so
                // iterate day-by-day up to (but excluding) the end instant's date — a complete,
                // gap-filled axis.
                val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
                val byDay = rows.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
                val noSessionByDay = noSessionRows.groupBy { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
                buildList {
                    var date = startDate
                    while (date < endDate) {
                        val sessions = byDay[date]
                        val orphans = noSessionByDay[date]
                        add(
                            DailyEarnings(
                                date = date,
                                gross = (sessions?.sumOf { it.gross } ?: 0.0) + (orphans?.sumOf { it.gross } ?: 0.0),
                                net = (sessions?.sumOf { it.net } ?: 0.0) + (orphans?.sumOf { it.net } ?: 0.0),
                                // #973: each orphan ROW is one delivery, so its count is the row count —
                                // the same bucketing as the gross/net on this bar.
                                deliveries = (sessions?.sumOf { it.deliveries } ?: 0) + (orphans?.size ?: 0),
                            ),
                        )
                        date = date.plusDays(1)
                    }
                }
            }
        }

    /** Recent dashes, newest first (future hub / #650). */
    fun recentSessions(limit: Int = 20): Flow<List<SessionRecord>> =
        analyticsDao.recentSessions(limit).map { rows -> rows.map { it.toDomain() } }

    /** Every delivery in a session, completion order (future #650 drill-down). */
    fun deliveriesForSession(sessionId: String): Flow<List<DeliveryRecord>> =
        analyticsDao.deliveriesForSession(sessionId).map { rows -> rows.map { it.toDomain() } }

    /**
     * The orphan "(No session)" deliveries for [period], newest first (#660 piece 2) — the categorize
     * flow's list (the Money-tab callout opens it). Reactive: the list re-emits as the projector folds a
     * `DELIVERY_SESSION_ASSIGN` (the tapped row leaves the bucket, no longer `sessionId IS NULL`). No new
     * PII surface (store names are driver-owned; no customer hashes on [DeliveryRecord]).
     */
    fun noSessionDeliveries(period: AnalyticsPeriod): Flow<List<DeliveryRecord>> =
        noSessionDeliveriesIn(periodBoundariesFlow(period))

    /** Arbitrary-window variant of [noSessionDeliveries] (#970 §7.1). */
    fun noSessionDeliveries(
        window: AnalyticsWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<DeliveryRecord>> = noSessionDeliveriesIn(flowOf(PeriodBounds.of(window, zone)))

    private fun noSessionDeliveriesIn(bounds: Flow<PeriodBounds.Bounds>): Flow<List<DeliveryRecord>> =
        bounds.flatMapLatest { (start, end) ->
            analyticsDao.noSessionDeliveryRows(start, end).map { rows -> rows.map { it.toDomain() } }
        }

    /**
     * The orphan-offer mismatches for [period] (#810 B2 Tier 2) — the driver-attestation surface. Joins
     * each `JOB_ACCEPT_MISMATCH` event (whose payload carries the closing job's accepted offer hashes +
     * the mismatch counts) to the ACCEPTED `offer_records`. The projector's Tier-1 store-evidence join
     * has already auto-resolved every cross-store orphan (`outcomeResolved = UNASSIGNED_INFERRED`), so
     * this surface is the same-store residue.
     *
     * A group is listed while its mismatch owes ≥1 orphan (`orphansOwed > 0`) and its accepted offers
     * are present — **including once its orphans are resolved** (review F3): keeping a resolved group
     * visible is what makes the mis-tap UNDO reachable in the primary 1-owed/2-accept shape (the
     * callout gates on the still-OWED count via [OrphanOfferGroup.owedRemaining], so a fully-resolved
     * group no longer contributes to the callout, but its rows stay in the dialog for undo). Residual:
     * if EVERY listed group is fully resolved the callout hides, so re-opening to undo the very last
     * resolution isn't reachable until a new mismatch appears (documented).
     *
     * Reactive: re-emits as the projector folds a mismatch, folds an `OFFER_OUTCOME_CORRECTION`, or
     * Tier-1 resolves one. Period-anchored on the mismatch event's `occurredAt` (the close time),
     * consistent with the Money-tab window. No new PII surface — store/pay/time are merchant/decision
     * data; no customer hashes.
     */
    fun orphanOfferGroups(period: AnalyticsPeriod): Flow<List<OrphanOfferGroup>> =
        orphanOfferGroupsIn(periodBoundariesFlow(period))

    /** Arbitrary-window variant of [orphanOfferGroups] (#970 §7.1). */
    fun orphanOfferGroups(
        window: AnalyticsWindow,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<OrphanOfferGroup>> = orphanOfferGroupsIn(flowOf(PeriodBounds.of(window, zone)))

    private fun orphanOfferGroupsIn(bounds: Flow<PeriodBounds.Bounds>): Flow<List<OrphanOfferGroup>> =
        bounds.flatMapLatest { (start, end) ->
            combine(
                appEventDao.getEventsByType(AppEventType.JOB_ACCEPT_MISMATCH),
                analyticsDao.acceptedOfferRecords(AppEventType.OFFER_ACCEPTED.name),
            ) { mismatchRows, offerRows ->
                val offersByKey: Map<Pair<String, String?>, OfferRecordEntity> =
                    offerRows.associateBy { it.offerHash to it.sessionId }
                mismatchRows.mapNotNull { row ->
                    if (row.occurredAt < start || row.occurredAt >= end) return@mapNotNull null
                    val payload = decodeMismatch(row.eventType, row.eventPayload) ?: return@mapNotNull null
                    val sid = row.aggregateId
                    val candidates = payload.acceptedOfferHashes
                        .mapNotNull { offersByKey[it to sid] }
                        .map { it.toOrphanCandidate() }
                    val owed = (payload.acceptedCount - payload.accountedCount).coerceAtLeast(0)
                    // List every owed mismatch whose offers we found — resolved OR open (F3: keep the
                    // undo reachable). owedRemaining (owed − resolved) drives the callout downstream.
                    if (owed > 0 && candidates.isNotEmpty()) {
                        OrphanOfferGroup(
                            jobId = payload.jobId,
                            sessionId = sid,
                            orphansOwed = owed,
                            offers = candidates.sortedBy { it.decidedAt },
                        )
                    } else {
                        null
                    }
                }.sortedByDescending { g -> g.offers.maxOfOrNull { it.decidedAt } ?: 0L }
            }
        }

    /** Decode a `JOB_ACCEPT_MISMATCH` payload for the Tier-2 read; fail-null on a malformed row. */
    private fun decodeMismatch(type: AppEventType, payloadJson: String): JobAcceptMismatchPayload? =
        try {
            AppEventCodec.decodePayload(type, payloadJson) as? JobAcceptMismatchPayload
        } catch (_: Exception) {
            null
        }

    private fun OfferRecordEntity.toOrphanCandidate() = OrphanOfferCandidate(
        offerEventSequenceId = eventSequenceId,
        storeName = merchantName,
        payAmount = payAmount,
        decidedAt = decidedAt,
        resolved = outcomeResolved != null,
    )

    /**
     * The **candidate dashes** an orphan delivery could be categorized into (#660 piece 2): ENDED
     * sessions within ±48 h of [completedAt], same [platform] (ALL platforms when the orphan's platform
     * is [Platform.Unknown]), nearest-in-**span** first. A one-shot snapshot fetched when the driver opens
     * the picker (not a live surface — the picker is a transient dialog); the projector's ended-session
     * guard is the authority, this is the UI-policy pre-filter over the existing [AnalyticsDao.sessionsBetween]
     * raw read (no new DAO query). No economy dependency, no new PII surface (session metadata only).
     *
     * **Ranked by distance to the session's [startedAt, endedAt] SPAN, not to its start instant** — a
     * mid-dash-restart orphan completes hours INTO its real dash, so sorting on `abs(startedAt −
     * completedAt)` would rank a short later dash above the long dash that actually contains the orphan.
     * A completion inside the span scores 0 (the containing dash always wins); otherwise it scores the
     * gap to the nearer endpoint. Tie-break by [startedAt] so the ordering is deterministic when two
     * candidates are equidistant. `endedAt` is non-null here (the ENDED filter above ran first).
     */
    suspend fun candidateSessionsForOrphan(completedAt: Long, platform: Platform): List<SessionRecord> {
        val start = completedAt - CANDIDATE_WINDOW_MILLIS
        val end = completedAt + CANDIDATE_WINDOW_MILLIS
        return analyticsDao.sessionsBetween(start, end)
            .map { it.toDomain() }
            .filter { it.endedAt != null }
            .filter { platform == Platform.Unknown || it.platform == platform }
            .sortedWith(compareBy({ spanDistance(it, completedAt) }, { it.startedAt }))
    }

    /** Distance from [completedAt] to a session's `[startedAt, endedAt]` span: 0 when inside the span,
     *  else the gap to the nearer endpoint. `endedAt` is non-null at every call site (ENDED-filtered). */
    private fun spanDistance(session: SessionRecord, completedAt: Long): Long {
        val startedAt = session.startedAt
        val endedAt = session.endedAt ?: startedAt
        return when {
            completedAt < startedAt -> startedAt - completedAt
            completedAt > endedAt -> completedAt - endedAt
            else -> 0L
        }
    }

    /**
     * One dash fully expanded (#650 PR A drill-down): the session header + its deliveries in
     * completion order, as a [SessionDetail], or `null` when no session row exists for [sessionId]
     * (the dash was never recorded, or the projector wiped/rebuilt). Read-model only, reactive by
     * construction (both sources are Room-invalidation Flows, so the detail re-emits as the projector
     * folds each delivery), no economy dependency (the per-delivery net is frozen), no new PII surface
     * (store names are driver-owned; no customer hashes are exposed on [DeliveryRecord]).
     */
    fun sessionDetail(sessionId: String): Flow<SessionDetail?> =
        combine(
            analyticsDao.sessionRecordFlow(sessionId),
            analyticsDao.deliveriesForSession(sessionId),
        ) { session, deliveries ->
            session?.let { SessionDetail(it.toDomain(), deliveries.map { row -> row.toDomain() }) }
        }

    /**
     * Build the free-tier CSV export (#319) — deliveries / sessions / summary — for the raw rows in
     * `[start, end)` (delivery `completedAt` / session `startedAt`). v1 exports all-time (the default
     * full range). Pure formatting is delegated to [CsvExporter]; this only supplies the rows, the
     * device [zone], and the export timestamp. Suspend, one-shot (not a Flow) — an export is a
     * snapshot the driver takes, not a live surface. No PII: customer/address hashes are excluded by
     * the exporter; store/merchant names are driver-owned and included.
     */
    suspend fun buildCsvExport(
        zone: ZoneId,
        generatedAtMillis: Long,
        start: Long = Long.MIN_VALUE,
        end: Long = Long.MAX_VALUE,
    ): CsvExporter.Bundle {
        val deliveries = analyticsDao.deliveriesBetween(start, end)
        val sessions = analyticsDao.sessionsBetween(start, end)
        return CsvExporter.export(deliveries, sessions, zone, generatedAtMillis)
    }

    /**
     * Fold the DAO period rows into [PeriodEconomics]. Net is frozen (Σ delivery net +
     * unattributed); the rates divide that frozen net by the period's hours/miles via
     * the [NetProfit] null-safe helpers (a rate stays `null` until its denominator is
     * measurable — no fabricated `$0/hr`).
     */
    private fun assemble(
        deliveredPay: Double,
        deliveryNet: Double,
        deliveries: Int,
        jobs: Int,
        miles: Double,
        onlineMillis: Long,
        gross: Double,
        unattributed: Double,
        overAttributed: Double,
        fuelCost: Double?,
        nonFuelCost: Double?,
        cash: Double,
        noSessionPay: Double = 0.0,
        noSessionDeliveries: Int = 0,
    ): PeriodEconomics {
        // Locked accounting (#688): cash tips add to BOTH net and gross. [gross] already includes the
        // cash sum (folded in the DAO's `grossAndUnattributed`); net adds it here (it is deliberately
        // OUTSIDE the frozen delivery `netProfit`, so it can't be lost to a null-net row). The
        // waterfall's cost = gross − net is unchanged (cash cancels), so the 4-step reconcile holds.
        // [overAttributed] (#701) is deliberately NOT folded into [net] — display-only review signal.
        // [noSessionPay] (#660) is a delivery-side pay/cash sum ALREADY inside [deliveryNet]/[cash]
        // (deliveryTotals counts a null-session row by its own completedAt, #655) — it is folded into
        // [gross] here (which was otherwise purely session-anchored and never saw it) so gross can no
        // longer read below net purely from this seam; it is NOT added a second time to net.
        //
        // KNOWN OVERSTATEMENT (#660 piece 1, adjudicated): this fold assumes the orphan delivery's pay
        // is genuinely NOT already inside any surviving session's `reportedEarnings`. That assumption can
        // be wrong — e.g. a mid-dash accessibility-service/app restart that loses this one delivery's
        // `sessionId` while the dash's summary screen still gets captured afterward: the summary's
        // `reportedEarnings` (folded via [grossAndUnattributed]) already reflects that delivery's pay, so
        // adding [noSessionPay] on top double-counts those dollars into [PeriodEconomics.grossEarnings],
        // and the SAME dollars surface in both the unattributed-review callout AND the "(No session)"
        // callout on the Money tab. This mirrors the pre-existing net-side overlap (an orphan's frozen net
        // was always folded via [deliveryTotals], with the identical correlated-restart caveat) — it is
        // not a new class of bug, just newly visible on the gross side now that this bucket folds in. The
        // real fix SHIPPED as #660 piece 2 (`DELIVERY_SESSION_ASSIGN` — [CorrectionRepository.assignDeliverySession]
        // → [AnalyticsProjector.applySessionAssign]): the driver categorizes the orphan into its real dash,
        // which removes the row from this bucket entirely (its `sessionId` flips non-null) and heals the
        // double-count via the existing session join, rather than trying to detect the overlap here. Until
        // the driver categorizes a given orphan this fold remains a documented, desk-verifiable overstatement,
        // not a defect to patch here (piece 1 kept it visible on purpose so the driver can act on it).
        val net = deliveryNet + unattributed + cash
        val hours = onlineMillis / 3_600_000.0
        return PeriodEconomics(
            totals = PeriodTotals(
                earnings = deliveredPay,
                miles = miles,
                deliveries = deliveries,
                jobs = jobs,
                onlineDuration = onlineMillis,
            ),
            grossEarnings = gross + noSessionPay,
            netProfit = net,
            unattributedPay = unattributed,
            netPerHour = NetProfit.perHour(net, hours),
            netPerMile = NetProfit.perMile(net, miles),
            // Frozen fuel/non-fuel cost rows for the 4-step waterfall — passed through as the DAO's
            // nullable SUMs (null = no frozen split coverage → the UI falls back to 3-step, #659).
            fuelCost = fuelCost,
            nonFuelCost = nonFuelCost,
            overAttributedPay = overAttributed,
            noSessionPay = noSessionPay,
            noSessionDeliveries = noSessionDeliveries,
        )
    }

    /**
     * Fold the two per-outcome DAO row sets into [DecisionEconomics]. Outcomes are matched by
     * [AppEventType] name (the SSOT the projector writes — no magic-string literals): a missing
     * outcome group means zero of that outcome (never present when its count is zero). The
     * acceptance rate stays `null` until at least one offer closed; the average score / est-$/hr
     * pass the DAO's null-safe `AVG` through verbatim (no fabricated zero).
     */
    private fun assembleDecisions(
        outcomes: List<OutcomeCountRow>,
        scores: List<ScoreOutcomeRow>,
    ): DecisionEconomics {
        fun outcome(type: AppEventType) = outcomes.find { it.outcome == type.name }
        fun scoreOf(type: AppEventType) = scores.find { it.outcome == type.name }

        val accepted = outcome(AppEventType.OFFER_ACCEPTED)?.count ?: 0
        val declined = outcome(AppEventType.OFFER_DECLINED)?.count ?: 0
        val timedOut = outcome(AppEventType.OFFER_TIMEOUT)?.count ?: 0
        val received = accepted + declined + timedOut

        val acceptedScores = scoreOf(AppEventType.OFFER_ACCEPTED)
        val declinedScores = scoreOf(AppEventType.OFFER_DECLINED)

        return DecisionEconomics(
            received = received,
            accepted = accepted,
            declined = declined,
            timedOut = timedOut,
            acceptanceRate = if (received > 0) accepted.toDouble() / received else null,
            declinedEstNet = outcome(AppEventType.OFFER_DECLINED)?.estNetSum ?: 0.0,
            // #975: how many of those declines were priced at all. `SUM(estNetPay)` already skips a
            // #936 no-verdict offer, so the figure above is honest but silently partial — this is what
            // lets the surface say "across N of M declines with estimates" rather than imply full
            // coverage (§9).
            declinedWithEstimate = outcome(AppEventType.OFFER_DECLINED)?.withEstimate ?: 0,
            avgScoreAccepted = acceptedScores?.avgScore,
            avgScoreDeclined = declinedScores?.avgScore,
            avgEstPerHourAccepted = acceptedScores?.avgEstPerHour,
            avgEstPerHourDeclined = declinedScores?.avgEstPerHour,
        )
    }

    /**
     * Fold the session + delivery-time DAO rows into [TimeEconomics] (#315 H4). A straight pass-through
     * of measured aggregates — nullable delivery SUMs stay nullable (no fabricated zero), and every
     * derived split ([TimeEconomics.unattributedMillis]/[TimeEconomics.unattributedMiles] etc.) is the
     * DTO's own coerce-≥0 helper, so this repository stores no second copy of that math (Principle 5).
     */
    private fun assembleTime(
        session: SessionTotalsRow,
        delivery: DeliveryTimeTotalsRow,
        pickup: PickupDwellTotalsRow,
    ): TimeEconomics = TimeEconomics(
        sessions = session.sessions,
        onlineMillis = session.onlineMillis,
        deliveryMinutes = delivery.deliveryMinutes,
        miles = session.miles,
        deliveryMiles = delivery.deliveryMiles,
        deliveriesWithDeadline = delivery.withDeadline,
        onTimeDeliveries = delivery.onTime,
        avgDeadlineMarginMillis = delivery.avgDeadlineMarginMillis,
        deliveries = delivery.deliveries,
        dropoffDwellMillis = delivery.dropoffDwellMillis,
        dropoffsTimed = delivery.dropoffsTimed,
        pickups = pickup.pickups,
        pickupDwellMillis = pickup.dwellMillis,
        pickupsTimed = pickup.timed,
    )

    /**
     * Public only for the two Offers-tab paging constants below (#975) — the hub's list needs the
     * same page size and the same ceiling the read enforces, and a second copy in the UI is exactly
     * the kind of hand-maintained duplicate Principle 5 exists to prevent. Everything else here stays
     * `private`.
     */
    companion object {
        /** ±window around an orphan's `completedAt` for the #660 piece-2 categorize picker (48 h). */
        private const val CANDIDATE_WINDOW_MILLIS = 48L * 60L * 60L * 1_000L

        /**
         * Longest day axis [dailyEarnings] will enumerate (#970) — a little over a year. Beyond it the
         * per-day list stops being a chart and starts being a bounded-ingestion hazard, so the window
         * variant returns an empty axis and the UI hides the card instead.
         */
        private const val MAX_DAY_AXIS = 400L

        /** Rows the Offers-tab list shows before the driver asks for more (#975 / brief §7.4). */
        const val DEFAULT_OFFER_PAGE = 10

        /**
         * Hard ceiling on one offers page (#975). The `See all N offers` footer expands the page in
         * place, so this is what keeps "N" from becoming an unbounded read on a Lifetime window — a
         * bounded-ingestion guard owned by the read side, not by the UI (Principle 6). Beyond it the
         * list shows the most recent [MAX_OFFER_PAGE] and says so.
         */
        const val MAX_OFFER_PAGE = 250
    }
}
