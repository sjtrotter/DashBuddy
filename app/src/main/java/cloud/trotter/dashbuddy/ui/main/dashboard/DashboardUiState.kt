package cloud.trotter.dashbuddy.ui.main.dashboard

import cloud.trotter.dashbuddy.domain.evaluation.NetProfit
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.format.formatDuration

/**
 * Immutable state for the home (Dashboard) screen (Principle 1 — UDF). Bundles the
 * first-run / status gate with a live "this dash" economics [glance]. Permissions
 * stay a composable-local OS re-check (they must be re-read on every `ON_RESUME`),
 * so they are intentionally NOT modelled here.
 */
data class DashboardUiState(
    val isFirstRun: Boolean = true,
    val statusText: String = "Offline",
    val isInSession: Boolean = false,
    val glance: DashGlance = DashGlance.EMPTY,
)

/**
 * The live "this dash" glance. Holds the *anchors* (net, miles, session start),
 * not frozen display values — the composable derives the ticking figures ($/hr,
 * online duration) from these + a `rememberNow()` tick so they stay fresh without
 * a state-machine transition (Reactive UI rules 1–3). `trueNet`/`miles` update
 * reactively through their own Flows; only the time-derived rates need the ticker.
 *
 * Labelled "This dash" (not "Today") on purpose: this is the current online
 * session only. Real Today/Week/Lifetime totals arrive with the read-model (#314),
 * out of scope here.
 */
data class DashGlance(
    val isInSession: Boolean,
    /** Net profit so far this dash: session gross − session miles × operating cost/mi. */
    val trueNet: Double,
    val miles: Double,
    /** Session start anchor for the online-duration / $-per-hour derivations; null when idle. */
    val startedAt: Long?,
) {
    val isPositiveNet: Boolean get() = trueNet >= 0.0

    /** True Net as money, or an em dash when not dashing. */
    val trueNetText: String get() = if (isInSession) Formats.money(trueNet) else EMPTY_VALUE

    /** Session miles with one decimal, or an em dash when not dashing. */
    val milesText: String get() = if (isInSession) Formats.decimal(miles) else EMPTY_VALUE

    /** Online hours elapsed at [now]; null when idle. Floors at 0 (never negative). */
    private fun onlineHoursAt(now: Long): Double? =
        startedAt?.let { (now - it).coerceAtLeast(0L) / 3_600_000.0 }

    /**
     * Net $/hr at wall-clock [now] — the denominator (online time) grows every
     * second, so this is derived in the composable from a ticker, never frozen in
     * state. `null` until a measurable slice of time has elapsed.
     */
    fun netPerHourAt(now: Long): Double? =
        onlineHoursAt(now)?.let { NetProfit.perHour(trueNet, it) }

    /** Net $/hr as money at [now], or an em dash when undefined / idle. */
    fun netPerHourText(now: Long): String =
        netPerHourAt(now)?.let { Formats.money(it) } ?: EMPTY_VALUE

    /** Online duration string at [now] via the TimeFormats SSOT; em dash when idle. */
    fun onlineDurationText(now: Long): String =
        startedAt?.let { formatDuration(now - it) } ?: EMPTY_VALUE

    companion object {
        /** Placeholder shown for every glance figure when not in a session. */
        const val EMPTY_VALUE = "—"

        val EMPTY = DashGlance(isInSession = false, trueNet = 0.0, miles = 0.0, startedAt = null)

        /** Build a live glance from the current session's anchors. */
        fun live(trueNet: Double, miles: Double, startedAt: Long): DashGlance =
            DashGlance(isInSession = true, trueNet = trueNet, miles = miles, startedAt = startedAt)
    }
}
