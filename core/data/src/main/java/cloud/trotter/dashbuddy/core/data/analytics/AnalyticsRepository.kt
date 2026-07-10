package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.DeliveryTimeTotalsRow
import cloud.trotter.dashbuddy.core.database.analytics.OutcomeCountRow
import cloud.trotter.dashbuddy.core.database.analytics.ScoreOutcomeRow
import cloud.trotter.dashbuddy.core.database.analytics.SessionTotalsRow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.DailyEarnings
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.DeliveryNet
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmapCalculator
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import cloud.trotter.dashbuddy.domain.analytics.SessionDetail
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionSpan
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard
import cloud.trotter.dashbuddy.domain.analytics.TimeEconomics
import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.StoreKeys
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil
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
        periodBoundariesFlow(period).flatMapLatest { (start, end) ->
            if (platform == null) {
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
            } else {
                val wire = platform.wire
                combine(
                    analyticsDao.deliveryTotalsByPlatform(start, end),
                    analyticsDao.sessionTotalsByPlatform(start, end),
                    analyticsDao.grossAndUnattributedByPlatform(start, end),
                    analyticsDao.noSessionTotalsByPlatform(start, end),
                ) { dl, sl, gl, nsl ->
                    val d = dl.find { it.platform == wire }
                    val s = sl.find { it.platform == wire }
                    val g = gl.find { it.platform == wire }
                    val ns = nsl.find { it.platform == wire }
                    assemble(
                        deliveredPay = d?.pay ?: 0.0, deliveryNet = d?.net ?: 0.0,
                        deliveries = d?.deliveries ?: 0, jobs = d?.jobs ?: 0,
                        miles = s?.miles ?: 0.0, onlineMillis = s?.onlineMillis ?: 0L,
                        gross = g?.gross ?: 0.0, unattributed = g?.unattributed ?: 0.0,
                        overAttributed = g?.overAttributed ?: 0.0,
                        fuelCost = d?.fuelCost, nonFuelCost = d?.nonFuelCost, cash = d?.cash ?: 0.0,
                        noSessionPay = (ns?.pay ?: 0.0) + (ns?.cash ?: 0.0),
                        noSessionDeliveries = ns?.deliveries ?: 0,
                    )
                }
            }
        }

    /**
     * Per-store economics for [period], highest-**gross** store first — frozen net + realized gross,
     * rolled up to CHAIN level (#159 F9). Both a resolved keyed location (`…|target|02426`) and an
     * unresolved/MANUAL raw row (`Target`) on the same platform fold into ONE bucket keyed
     * `platform + "|" + normalizedChain` (chain from the storeKey's middle segment when keyed, else the
     * shared `:domain` normalizer over `storeName`). This is the F9 sentence — "Target" (unresolved) and
     * "doordash|target|02426" share a bucket — the cross-platform parity (F5), and the fix for multiple
     * identical-"Target" rows fragmenting the list. Per-LOCATION detail stays the report card's job
     * ([storeReportCards], grouped by `storeKey`).
     *
     * Display name prefers the chain's first-observed `stores.chainDisplay` (a single extra reactive DAO
     * read), else the first row's raw `storeName`.
     *
     * Cash tips (#688 F5) add to BOTH gross and net (explicit adds here, per the locked accounting). The
     * DAO's `ORDER BY pay DESC` is a cash-free pre-sort; the final rank is re-sorted here by the
     * cash-inclusive [StoreEconomics.gross].
     *
     * **Null-net + cash presentation:** a bucket whose rows all have a null frozen `net` (no cost basis)
     * but a recorded `cashTip` surfaces as `net = 0 + cash`. Accepted: cash has no cost term to net out.
     */
    fun perStoreEconomics(period: AnalyticsPeriod): Flow<List<StoreEconomics>> =
        periodBoundariesFlow(period).flatMapLatest { (start, end) ->
            combine(
                analyticsDao.deliveryTotalsByStore(start, end),
                analyticsDao.storeChainDisplays(),
            ) { rows, displays ->
                val displayByBucket = displays.associate {
                    (it.platform + "|" + it.normalizedChain) to it.chainDisplay
                }
                rows.groupBy { chainBucket(it) }
                    .map { (bucket, group) ->
                        val first = group.first()
                        StoreEconomics(
                            storeKey = bucket, // chain-level bucket identity (not a per-location storeKey)
                            storeName = displayByBucket[bucket] ?: first.storeName,
                            net = group.sumOf { it.net + it.cash },
                            gross = group.sumOf { it.pay + it.cash },
                            deliveries = group.sumOf { it.deliveries },
                        )
                    }.sortedByDescending { it.gross }
            }
        }

    /** The F9 chain bucket for a store totals row: `platform + "|" + normalizedChain`, chain taken from
     *  the storeKey's middle segment when keyed, else the shared `:domain` normalizer over storeName. */
    private fun chainBucket(row: cloud.trotter.dashbuddy.core.database.analytics.StoreTotalsRow): String {
        val chain = row.storeKey?.split("|")?.getOrNull(1)?.takeIf { it.isNotEmpty() }
            ?: StoreKeys.normalizedChain(row.storeName.orEmpty())
        return row.platform + "|" + chain
    }

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
                    p50DwellMillis = percentile(sorted, 0.50),
                    p95DwellMillis = percentile(sorted, 0.95),
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
        periodBoundariesFlow(period).flatMapLatest { (start, end) ->
            combine(
                analyticsDao.offerOutcomes(start, end),
                analyticsDao.offerScoreOutcomes(start, end),
            ) { outcomes, scores -> assembleDecisions(outcomes, scores) }
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
        periodBoundariesFlow(period).flatMapLatest { (start, end) ->
            combine(
                analyticsDao.sessionTotals(start, end),
                analyticsDao.deliveryTimeTotals(start, end),
            ) { s, d -> assembleTime(s, d) }
        }

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
        return periodBoundariesFlow(period, zone).flatMapLatest { (start, end) ->
            combine(
                analyticsDao.sessionGrossRows(start, end),
                analyticsDao.noSessionDailyRows(start, end),
            ) { rows, noSessionRows ->
                // The end bound is exactly the next period's local midnight (PeriodBounds), so iterate
                // day-by-day up to (but excluding) the end instant's date — a complete, gap-filled axis.
                val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
                val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
                val byDay = rows.groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
                val noSessionByDay = noSessionRows.groupBy { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
                buildList {
                    var date = startDate
                    while (date < endDate) {
                        val sessionGross = byDay[date]?.sumOf { it.gross } ?: 0.0
                        val noSessionGross = noSessionByDay[date]?.sumOf { it.gross } ?: 0.0
                        add(DailyEarnings(date, sessionGross + noSessionGross))
                        date = date.plusDays(1)
                    }
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
        // real fix is #660 piece 2 (categorize an orphan delivery into its real session via a correction
        // event), which removes the row from this bucket entirely rather than trying to detect the overlap
        // here; until then this is a documented, desk-verifiable edge, not a defect to patch in piece 1.
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
    /**
     * The [p]-th percentile of an ASCENDING-sorted dwell list (#159 store report card) — nearest-rank
     * (SQLite has no native percentile and store visit counts are small, so an exact in-Kotlin nearest-
     * rank is honest and cheap). Null on an empty list. `p ∈ [0,1]`.
     */
    private fun percentile(sorted: List<Long>, p: Double): Long? {
        if (sorted.isEmpty()) return null
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun assembleTime(
        session: SessionTotalsRow,
        delivery: DeliveryTimeTotalsRow,
    ): TimeEconomics = TimeEconomics(
        sessions = session.sessions,
        onlineMillis = session.onlineMillis,
        deliveryMinutes = delivery.deliveryMinutes,
        miles = session.miles,
        deliveryMiles = delivery.deliveryMiles,
        deliveriesWithDeadline = delivery.withDeadline,
        onTimeDeliveries = delivery.onTime,
        avgDeadlineMarginMillis = delivery.avgDeadlineMarginMillis,
    )
}
