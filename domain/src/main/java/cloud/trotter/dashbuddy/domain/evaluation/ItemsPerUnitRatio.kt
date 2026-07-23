package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.state.Platform

/** The persisted learned items:units ratio: the running mean and the shops behind it (#823). */
data class LearnedItemsPerUnitRatio(val ratio: Double?, val sampleCount: Int)

/**
 * The cold-start seed items:units ratio **per platform** (#823 Phase 1), mirroring [ShopRateSeeds]
 * (#588). Until a platform has crossed [ItemsPerUnitRatio.MIN_SAMPLES] on its OWN measured
 * units-denominated shops, the units→items-equivalent conversion for the Shop & Deliver time
 * estimate falls back to that platform's seed here.
 *
 * The value is **data, not logic** (P8) — the shape is `Map<Platform, Double>`, so a second
 * platform's own measured seed drops in as one map entry, never a `when (platform)`. Same
 * `(platform, …)` key-space the #588 shop-rate seeds and #159's per-store tier inherit.
 */
object ItemsPerUnitRatioSeeds {

    /**
     * DoorDash's corpus-derived seed. The 2026-07 capture corpus's `(N items • M units)` pairs
     * (63/79 ≈ 0.80, 22/31 ≈ 0.71, 9/11 ≈ 0.82) cluster at ≈ 0.75–0.81; 0.78 is the seeded midpoint
     * (#823 desk analysis). This is the ONE anchor for that fact — every other comment/constant that
     * wants "the DoorDash items:units seed" should point here, not re-declare the literal.
     */
    const val DOORDASH_CORPUS_SEED: Double = 0.78

    /**
     * Generic fallback ratio for a platform with no bespoke seed of its own. Today this ADOPTS
     * [DOORDASH_CORPUS_SEED] wholesale — the only measured corpus we have — as a placeholder until
     * that platform earns its own independently-measured entry.
     */
    const val DEFAULT: Double = DOORDASH_CORPUS_SEED

    private val byPlatform: Map<Platform, Double> = mapOf(
        Platform.DoorDash to DOORDASH_CORPUS_SEED,
    )

    /** The seed ratio for [platform] — its bespoke corpus entry, else the generic [DEFAULT]. */
    fun seedFor(platform: Platform): Double = byPlatform[platform] ?: DEFAULT
}

/**
 * Folds measured (units-denominated) shops into the dasher's learned items:units ratio (#823
 * Phase 1). Pure and order-independent (a running incremental mean), the exact discipline of
 * [ShopRate] — a units-only offer quoted [units], the shop screen later reported [items] actually
 * shopped, and their ratio is one sample of "how many physical items a DoorDash unit is worth".
 *
 * Each sample's ratio is **clamped to [MIN_RATIO]..[MAX_RATIO]** before folding, so a single
 * outlier basket (a very-multi-pack order) can't drag the mean out of a sane band; the seed itself
 * lives inside that band. [MIN_SAMPLES] gates trusting the learned mean over the seed, exactly like
 * the shop-rate trust gate.
 */
object ItemsPerUnitRatio {

    /** Min items shopped in a measured sample before it counts — a 1–2 item shop is noise. */
    const val MIN_ITEMS = 3

    /** Sane band for the ratio (#823): a DoorDash unit is between half an item and one item. */
    const val MIN_RATIO = 0.5
    const val MAX_RATIO = 1.0

    /** Measured units-shops required before the learned ratio overrides the seed. */
    const val MIN_SAMPLES = 5

    /** A measured sample is in-band (worth folding) only with real units + enough items shopped. */
    fun isValidSample(units: Int, items: Int): Boolean = units > 0 && items >= MIN_ITEMS

    /**
     * Fold one measured units-shop ([items] actually shopped for a quoted-[units] offer) into the
     * running mean ratio + sample count. Out-of-band samples are ignored (returns the prior pair
     * unchanged). The per-sample ratio is clamped into [MIN_RATIO]..[MAX_RATIO]; the mean is
     * incremental — `avg + (rate - avg) / n` — so it's stable and independent of fold order.
     */
    fun fold(prevAvg: Double?, prevN: Int, units: Int, items: Int): Pair<Double?, Int> {
        if (!isValidSample(units, items)) return prevAvg to prevN
        val ratio = (items.toDouble() / units.toDouble()).coerceIn(MIN_RATIO, MAX_RATIO)
        val n = (prevN.coerceAtLeast(0)) + 1
        val avg = if (prevAvg == null || prevN <= 0) ratio else prevAvg + (ratio - prevAvg) / n
        return avg to n
    }
}
