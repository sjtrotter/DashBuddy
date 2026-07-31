package cloud.trotter.dashbuddy.ui.main.analytics

import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmap
import cloud.trotter.dashbuddy.domain.analytics.EarningsHeatmapCell
import cloud.trotter.dashbuddy.domain.analytics.StoreReportCard

/**
 * Pure, Compose-free logic for the Patterns tab's #979 leaderboard + heatmap toggle (redesign stage
 * 5/7, brief §6). Compose-free on purpose (the [WindowLabel]/`MoneyWentModel` precedent) — the
 * interesting part is sort/threshold reasoning, and it should be provable without rendering.
 */
object PatternsModel {

    // ── Heatmap Rate/Hours toggle ───────────────────────────────────────

    /** Which per-cell value the heatmap is currently tinted by — both are already computed on
     *  [EarningsHeatmapCell] (brief §6: "thrown away at render"), this just picks one. */
    enum class HeatmapMode { RATE, HOURS }

    /**
     * The value [mode] reads off [cell] for coloring. RATE reuses the existing masked
     * [EarningsHeatmapCell.dollarsPerHour] (null below the coverage floor). HOURS has no floor/mask
     * concept — every cell's [EarningsHeatmapCell.coverageHours] is a real measurement — so a
     * genuinely-zero (never-dashed) hour is folded to `null` here on purpose: it lets the caller feed
     * the result straight into [cloud.trotter.dashbuddy.core.designsystem.component.AppHeatScale.cellColor],
     * whose `rate == null` branch already renders the "no data" swatch, which is exactly what "never
     * dashed this hour" means in Hours mode. There is no Hours equivalent of Rate's third "worked, no
     * net" state — coverage has no notion of a bad outcome, only more or less of it.
     */
    fun cellValue(cell: EarningsHeatmapCell, mode: HeatmapMode): Double? = when (mode) {
        HeatmapMode.RATE -> cell.dollarsPerHour
        HeatmapMode.HOURS -> cell.coverageHours.takeIf { it > 0.0 }
    }

    /** The color-scale top for Hours mode — the driver's own most-covered hour-of-week cell (mirrors
     *  [EarningsHeatmap.maxDollarsPerHour] for Rate). Zero when the heatmap has no coverage at all. */
    fun maxCoverageHours(heatmap: EarningsHeatmap): Double =
        heatmap.cells.maxOfOrNull { it.coverageHours } ?: 0.0

    // ── Store leaderboard ───────────────────────────────────────────────

    /** Sort chips (brief §6): By net (best net first), By wait (shortest usual wait first — a
     *  leaderboard's rank 1 is the best-performing row under whichever criterion is selected), Recent
     *  (most recently visited first — the repository's own existing `ORDER BY lastSeenAt DESC`). */
    enum class LeaderboardSort { NET, WAIT, RECENT }

    /**
     * [cards] ordered for the leaderboard under [sort]. A store with no usual-wait sample (no pickup
     * dwell recorded yet) sorts to the BOTTOM of a By-wait ranking — an absent measurement is not a
     * fast time, and floating it to the top would misreport a data gap as a win. Ties break on
     * [StoreReportCard.chainDisplay] so the order is deterministic (never a re-shuffle on re-emit).
     */
    fun sortedStores(cards: List<StoreReportCard>, sort: LeaderboardSort): List<StoreReportCard> =
        when (sort) {
            LeaderboardSort.NET -> cards.sortedWith(compareByDescending<StoreReportCard> { it.net }.thenBy { it.chainDisplay })
            LeaderboardSort.WAIT -> cards.sortedWith(
                compareBy<StoreReportCard> { it.p50DwellMillis ?: Long.MAX_VALUE }.thenBy { it.chainDisplay },
            )
            LeaderboardSort.RECENT -> cards.sortedWith(compareByDescending<StoreReportCard> { it.lastSeenAt }.thenBy { it.chainDisplay })
        }

    /** The #1 (highest-net) store's net across [cards] — the proportional bar's scale top, held fixed
     *  regardless of the currently selected [LeaderboardSort] so switching sort chips never rescales
     *  the bars underneath the driver. Non-positive when no store has positive net. */
    fun maxNet(cards: List<StoreReportCard>): Double = cards.maxOfOrNull { it.net } ?: 0.0

    /**
     * How far [net] fills the proportional bar relative to [maxNet] (the #1 store). A non-positive
     * [net] or [maxNet] renders an empty bar — the bar communicates "how much of the best store's net"
     * and a store that lost money (or ties/leads a field with no positive net at all) has nothing
     * positive to show proportionally; its net FIGURE (rendered separately) still carries the real,
     * possibly-negative number.
     */
    fun netBarFraction(net: Double, maxNet: Double): Float {
        if (maxNet <= 0.0 || net <= 0.0) return 0f
        return (net / maxNet).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Below this many stores carrying a real usual-wait sample, "outlier" has no honest fleet to
     * compare against (§9 thin-data honesty) — every wait renders un-amber'd rather than the fleet
     * median itself being a near-meaningless 1- or 2-store figure.
     */
    const val MIN_FLEET_SAMPLE_FOR_OUTLIER = 3

    /**
     * A store's usual wait counts as an outlier once it's at least this many times the FLEET median
     * usual wait (computed across every store currently on the leaderboard with a real sample) —
     * "notably slower than everywhere else you go," never an absolute clock-time judgment. 1.5× is a
     * documented, deliberately-chosen threshold — there is no existing outlier convention anywhere
     * else in the codebase to anchor to; retune here if the field data proves it too noisy/quiet.
     */
    const val OUTLIER_MULTIPLIER = 1.5

    /**
     * True when [card]'s own [StoreReportCard.p50DwellMillis] is an outlier against the fleet median
     * of every OTHER sampled wait in [cards] (see [MIN_FLEET_SAMPLE_FOR_OUTLIER] / [OUTLIER_MULTIPLIER]
     * for the honesty floor + the chosen threshold). A store with no wait sample of its own is never an
     * outlier (nothing to flag).
     */
    fun isWaitOutlier(cards: List<StoreReportCard>, card: StoreReportCard): Boolean {
        val wait = card.p50DwellMillis ?: return false
        val samples = cards.mapNotNull { it.p50DwellMillis }
        if (samples.size < MIN_FLEET_SAMPLE_FOR_OUTLIER) return false
        val fleetMedian = medianOf(samples)
        if (fleetMedian <= 0L) return false
        return wait >= fleetMedian * OUTLIER_MULTIPLIER
    }

    private fun medianOf(values: List<Long>): Long {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }
}
