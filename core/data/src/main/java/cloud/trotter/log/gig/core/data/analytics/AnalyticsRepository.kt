package cloud.trotter.log.gig.core.data.analytics

import cloud.trotter.log.gig.domain.analytics.ExpenseRecord
import cloud.trotter.log.gig.domain.analytics.FuelRecord
import cloud.trotter.log.gig.core.database.analytics.AnalyticsDao
import cloud.trotter.log.gig.core.database.analytics.HudShiftDao
import cloud.trotter.log.gig.core.database.analytics.OfferRecordEntity
import cloud.trotter.log.gig.core.database.event.AppEventDao
import cloud.trotter.log.gig.domain.analytics.AnalyticsPeriod
import cloud.trotter.log.gig.domain.analytics.AnalyticsWindow
import cloud.trotter.log.gig.domain.analytics.DailyEarnings
import cloud.trotter.log.gig.domain.analytics.DeliveryNet
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.EarningsHeatmap
import cloud.trotter.log.gig.domain.analytics.EarningsHeatmapCalculator
import cloud.trotter.log.gig.domain.analytics.GrossSources
import cloud.trotter.log.gig.domain.analytics.HourOfWeekSampler
import cloud.trotter.log.gig.domain.analytics.HourOfWeekSamples
import cloud.trotter.log.gig.domain.analytics.OrphanOfferCandidate
import cloud.trotter.log.gig.domain.analytics.OrphanOfferGroup
import cloud.trotter.log.gig.domain.analytics.Percentiles
import cloud.trotter.log.gig.domain.analytics.PeriodEconomics
import cloud.trotter.log.gig.domain.analytics.PeriodTotals
import cloud.trotter.log.gig.domain.analytics.PlatformEconomics
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.SessionReportRule
import cloud.trotter.log.gig.domain.analytics.SessionSpan
import cloud.trotter.log.gig.domain.analytics.StoreReportCard
import cloud.trotter.log.gig.domain.evaluation.NetProfit
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.JobAcceptMismatchPayload
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicle
import cloud.trotter.log.gig.domain.model.vehicle.Vehicle
import cloud.trotter.log.gig.domain.state.Platform
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

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
    // #1459: the orphan picker's candidates come from the shift read that carries recorded pay, so each
    // candidate's effective report has the input its floor needs (kept out of the oversized AnalyticsDao).
    private val hudShiftDao: HudShiftDao,
) {
    val projectionWatermark: Flow<Long?>
        get() = analyticsDao.watermark().map { it?.watermarkSequenceId }

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
                        payWithoutNet = d.payWithoutNet,
                        noSessionPay = ns.pay + ns.cash, noSessionDeliveries = ns.deliveries,
                        grossSources = GrossSources(
                            summaryReported = g.summaryReported,
                            summaryDashes = g.summaryDashes,
                            counterReported = g.counterReported,
                            counterDashes = g.counterDashes,
                            driverSetReported = g.driverSetReported,
                            driverSetDashes = g.driverSetDashes,
                            recordedPayReported = g.recordedPayReported,
                            recordedPayDashes = g.recordedPayDashes,
                            recordedWithoutReport = g.recordedWithoutReport,
                            unreportedDashesWithPay = g.unreportedDashesWithPay,
                            unreportedDashesNoPay = g.unreportedDashesNoPay,
                            sessionCash = g.sessionCash,
                        ),
                    )
                }
            }
        }

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
                            payWithoutNet = d?.payWithoutNet ?: 0.0,
                            noSessionPay = (ns?.pay ?: 0.0) + (ns?.cash ?: 0.0),
                            noSessionDeliveries = ns?.deliveries ?: 0,
                            grossSources = g?.let {
                                GrossSources(
                                    summaryReported = it.summaryReported,
                                    summaryDashes = it.summaryDashes,
                                    counterReported = it.counterReported,
                                    counterDashes = it.counterDashes,
                                    driverSetReported = it.driverSetReported,
                                    driverSetDashes = it.driverSetDashes,
                                    recordedPayReported = it.recordedPayReported,
                                    recordedPayDashes = it.recordedPayDashes,
                                    recordedWithoutReport = it.recordedWithoutReport,
                                    unreportedDashesWithPay = it.unreportedDashesWithPay,
                                    unreportedDashesNoPay = it.unreportedDashesNoPay,
                                    sessionCash = it.sessionCash,
                                )
                            } ?: GrossSources.EMPTY,
                        ),
                    )
                }.sortedByDescending { it.economics.grossEarnings }
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

    /** A fresh, single-row read on each call, independent of any UI's collected snapshot. */
    suspend fun deliveryById(eventSequenceId: Long): DeliveryRecord? =
        analyticsDao.deliveryRecord(eventSequenceId)?.toDomain()

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
     * guard is the authority, this is the UI-policy pre-filter over the existing [HudShiftDao.endedSessionsBetween]
     * read (no new DAO query), which carries each shift's recorded pay for its effective report (#1459). No economy
     * dependency, no new PII surface (session metadata only).
     *
     * **Ranked by distance to the session's [startedAt, endedAt] SPAN, not to its start instant** — a
     * mid-dash-restart orphan completes hours INTO its real dash, so sorting on `abs(startedAt −
     * completedAt)` would rank a short later dash above the long dash that actually contains the orphan.
     * A completion inside the span scores 0 (the containing dash always wins); otherwise it scores the
     * gap to the nearer endpoint. Tie-break by [startedAt] so the ordering is deterministic when two
     * candidates are equidistant. `endedAt` is non-null here (the read returns ENDED shifts only).
     */
    suspend fun candidateSessionsForOrphan(completedAt: Long, platform: Platform): List<SessionRecord> {
        val start = completedAt - CANDIDATE_WINDOW_MILLIS
        val end = completedAt + CANDIDATE_WINDOW_MILLIS
        return hudShiftDao.endedSessionsBetween(start, end).first()
            .map { it.toDomain() }
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

    /** #1533: a shift's last recorded activity (`lastEventAt`), null before its row folds. */
    fun shiftLastActivityAt(sessionId: String): Flow<Long?> =
        analyticsDao.sessionRecordFlow(sessionId).map { it?.lastEventAt }

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
            session?.let {
                val records = deliveries.map { row -> row.toDomain() }
                SessionDetail(it.toDomain(SessionReportRule.recordedPayOf(records.map { row -> row.realizedPay })), records)
            }
        }

    /**
     * Build the free-tier CSV export (#319), including the supplied ledger projection rows, for analytics in
     * `[start, end)` (delivery `completedAt` / session `startedAt` / ledger `occurredAt`). v1 exports all-time (the default
     * full range). Pure formatting is delegated to [CsvExporter]; this only supplies the rows, the
     * device [zone], and the export timestamp. Suspend, one-shot (not a Flow) — an export is a
     * snapshot the driver takes, not a live surface. No PII: customer/address hashes are excluded by
     * the exporter; store/merchant names are driver-owned and included.
     * [dashVehicles] and [vehicles] pass through without range filtering: sessions resolve their
     * historical vehicle by id, including stamps and saved vehicles created outside the range.
     */
    suspend fun buildCsvExport(
        zone: ZoneId,
        generatedAtMillis: Long,
        start: Long = Long.MIN_VALUE,
        end: Long = Long.MAX_VALUE,
        expenses: List<ExpenseRecord> = emptyList(),
        fuel: List<FuelRecord> = emptyList(),
        dashVehicles: List<DashVehicle> = emptyList(),
        vehicles: List<Vehicle> = emptyList(),
    ): CsvExporter.Bundle {
        val deliveries = analyticsDao.deliveriesBetween(start, end)
        val sessions = analyticsDao.sessionsBetween(start, end)
        // #1459: an exported shift's effective report reads its recorded pay over ALL its deliveries, so a bounded
        // export (a shift's deliveries can complete outside the window) reads every delivery for that map.
        val allTime = start == Long.MIN_VALUE && end == Long.MAX_VALUE
        val everyDelivery = if (allTime) deliveries else analyticsDao.deliveriesBetween(Long.MIN_VALUE, Long.MAX_VALUE)
        return CsvExporter.export(
            deliveries, sessions, zone, generatedAtMillis,
            expenses.filter { it.occurredAt >= start && it.occurredAt < end },
            fuel.filter { it.occurredAt >= start && it.occurredAt < end },
            dashVehicles, vehicles,
            recordedPay = CsvExporter.recordedPayBySession(everyDelivery),
        )
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
        payWithoutNet: Double,
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
        grossSources: GrossSources,
    ): PeriodEconomics {
        // Locked accounting (#688): cash tips add to BOTH net and gross. [gross] already includes the
        // cash sum (folded in the DAO's `grossAndUnattributed`); net adds it here (it is deliberately
        // OUTSIDE the frozen delivery `netProfit`, so it can't be lost to a null-net row). The
        // derived cost subtracts payWithoutNet from gross − net; cash already cancels separately.
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
            payWithoutNet = payWithoutNet,
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
            grossSources = grossSources,
            cash = cash,
        )
    }

    private companion object {
        /** ±window around an orphan's `completedAt` for the #660 piece-2 categorize picker (48 h). */
        private const val CANDIDATE_WINDOW_MILLIS = 48L * 60L * 60L * 1_000L

        /**
         * Longest day axis [dailyEarnings] will enumerate (#970) — a little over a year. Beyond it the
         * per-day list stops being a chart and starts being a bounded-ingestion hazard, so the window
         * variant returns an empty axis and the UI hides the card instead.
         */
        private const val MAX_DAY_AXIS = 400L
    }
}
