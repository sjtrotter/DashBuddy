package cloud.trotter.dashbuddy.core.data.analytics

import cloud.trotter.dashbuddy.core.database.analytics.AnalyticsDao
import cloud.trotter.dashbuddy.core.database.analytics.OutcomeCountRow
import cloud.trotter.dashbuddy.core.database.analytics.ScoreOutcomeRow
import cloud.trotter.dashbuddy.domain.analytics.AnalyticsPeriod
import cloud.trotter.dashbuddy.domain.analytics.DecisionEconomics
import cloud.trotter.dashbuddy.domain.analytics.DeliveryRecord
import cloud.trotter.dashbuddy.domain.analytics.PeriodEconomics
import cloud.trotter.dashbuddy.domain.analytics.PeriodTotals
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.StoreEconomics
import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
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
                ) { d, s, g ->
                    assemble(
                        deliveredPay = d.pay, deliveryNet = d.net, deliveries = d.deliveries,
                        jobs = d.jobs, miles = s.miles, onlineMillis = s.onlineMillis,
                        gross = g.gross, unattributed = g.unattributed,
                    )
                }
            } else {
                val wire = platform.wire
                combine(
                    analyticsDao.deliveryTotalsByPlatform(start, end),
                    analyticsDao.sessionTotalsByPlatform(start, end),
                    analyticsDao.grossAndUnattributedByPlatform(start, end),
                ) { dl, sl, gl ->
                    val d = dl.find { it.platform == wire }
                    val s = sl.find { it.platform == wire }
                    val g = gl.find { it.platform == wire }
                    assemble(
                        deliveredPay = d?.pay ?: 0.0, deliveryNet = d?.net ?: 0.0,
                        deliveries = d?.deliveries ?: 0, jobs = d?.jobs ?: 0,
                        miles = s?.miles ?: 0.0, onlineMillis = s?.onlineMillis ?: 0L,
                        gross = g?.gross ?: 0.0, unattributed = g?.unattributed ?: 0.0,
                    )
                }
            }
        }

    /** Per-store economics for [period], highest-earning store first — frozen net + realized gross. */
    fun perStoreEconomics(period: AnalyticsPeriod): Flow<List<StoreEconomics>> =
        periodBoundariesFlow(period).flatMapLatest { (start, end) ->
            analyticsDao.deliveryTotalsByStore(start, end).map { rows ->
                rows.map { StoreEconomics(storeName = it.storeName, net = it.net, gross = it.pay, deliveries = it.deliveries) }
            }
        }

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

    /** Recent dashes, newest first (future hub / #650). */
    fun recentSessions(limit: Int = 20): Flow<List<SessionRecord>> =
        analyticsDao.recentSessions(limit).map { rows -> rows.map { it.toDomain() } }

    /** Every delivery in a session, completion order (future #650 drill-down). */
    fun deliveriesForSession(sessionId: String): Flow<List<DeliveryRecord>> =
        analyticsDao.deliveriesForSession(sessionId).map { rows -> rows.map { it.toDomain() } }

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
    ): PeriodEconomics {
        val net = deliveryNet + unattributed
        val hours = onlineMillis / 3_600_000.0
        return PeriodEconomics(
            totals = PeriodTotals(
                earnings = deliveredPay,
                miles = miles,
                deliveries = deliveries,
                jobs = jobs,
                onlineDuration = onlineMillis,
            ),
            grossEarnings = gross,
            netProfit = net,
            unattributedPay = unattributed,
            netPerHour = NetProfit.perHour(net, hours),
            netPerMile = NetProfit.perMile(net, miles),
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
}
