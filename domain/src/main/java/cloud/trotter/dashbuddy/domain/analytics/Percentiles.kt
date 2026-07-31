package cloud.trotter.dashbuddy.domain.analytics

import kotlin.math.ceil

/**
 * Nearest-rank percentiles over a small ASCENDING-sorted sample (#983).
 *
 * SQLite has no native percentile and every population that needs one here is small (a store's visits,
 * a window's between-job gaps), so an exact in-Kotlin nearest rank is both honest and cheap — no
 * interpolation, so every value returned is a value the driver actually experienced.
 *
 * One owner (Principle 5): the #159 store report card's dwell p50/p95 and the #983 §7.8 gap median/p90
 * are the same computation, and two copies of a percentile convention (nearest-rank vs interpolated,
 * 0- vs 1-based rank) is exactly the sort of duplicate that silently disagrees at the edges.
 */
object Percentiles {

    /**
     * The [p]-th percentile of [sorted] (ascending), nearest-rank. `p ∈ [0,1]`; null on an empty list.
     *
     * Nearest rank = `ceil(p × N)`, clamped into `1..N`, read 0-based — so `p = 0.5` over 4 samples is
     * the 2nd value (never the mean of the 2nd and 3rd), and `p = 1.0` is the maximum.
     */
    fun nearestRank(sorted: List<Long>, p: Double): Long? {
        if (sorted.isEmpty()) return null
        val rank = ceil(p * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }
}
