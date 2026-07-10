package cloud.trotter.dashbuddy.domain.evaluation

import cloud.trotter.dashbuddy.domain.state.Platform

/** The persisted learned shopping pace: the running mean items/min and the shops behind it (#556). */
data class LearnedShopRate(val itemsPerMin: Double?, val sampleCount: Int)

/**
 * The cold-start seed shopping pace (items/min) **per platform** (#588). Until a platform has crossed
 * the [UserEconomy.MIN_SHOP_SAMPLES] trust gate on its OWN measured shops, the Shop & Deliver time
 * estimate falls back to that platform's seed here — a DoorDash-learned pace can never seed an
 * Instacart / Uber shop, and vice-versa.
 *
 * Today only DoorDash carries a bespoke corpus-derived entry ([DOORDASH_CORPUS_SEED]); every
 * other platform resolves to [DEFAULT]. The value is **data, not logic** — the shape is
 * `Map<Platform, Double>`, so a second platform's measured seed (Instacart bin-scan / staging overhead
 * differs) drops in as one map entry, never a `when (platform)`. This is the same `(platform, …)`
 * key-space #159's per-store/chain tier and #254's learned constants inherit (P8).
 */
object ShopRateSeeds {

    /**
     * DoorDash's own measured corpus seed — median 0.79 (rounded to 0.8), IQR 0.66–0.92, across 48
     * real shops in the 2026-06 capture corpus (#556). This is the ONE anchor for that fact; every
     * other constant/comment that wants "the DoorDash corpus seed" should point here, not
     * re-declare the literal.
     */
    const val DOORDASH_CORPUS_SEED: Double = 0.8

    /**
     * Generic fallback pace for a platform with no bespoke seed of its own. Today this simply
     * ADOPTS [DOORDASH_CORPUS_SEED] wholesale — the only measured corpus we have — not because a
     * non-DoorDash shop is known to pace the same; it's a placeholder fallback until that platform
     * earns its own bespoke, independently-measured entry (#588).
     */
    const val DEFAULT: Double = DOORDASH_CORPUS_SEED

    private val byPlatform: Map<Platform, Double> = mapOf(
        Platform.DoorDash to DOORDASH_CORPUS_SEED,
    )

    /** The seed pace for [platform] — its bespoke corpus entry, else the generic [DEFAULT]. */
    fun seedFor(platform: Platform): Double = byPlatform[platform] ?: DEFAULT
}

/**
 * Folds measured shops into the dasher's learned overall shopping pace (#556). Pure and
 * order-independent (a running incremental mean), so the same function backs the live
 * per-shop update and, later, the post-session #159 store/chain projector.
 *
 * The rate is **effective** items/min — measured arrive→leave, so it already absorbs in-store
 * time + checkout + ID-check + wait (the same basis as [UserEconomy.DEFAULT_SHOP_ITEMS_PER_MIN]).
 */
object ShopRate {

    /** Min items in a measured shop before it counts — a 1–2 item "shop" is noise, not a pace sample. */
    const val MIN_ITEMS = 3

    /** Min in-store minutes before a sample counts — guards a sub-minute mis-timed sample. */
    const val MIN_MINUTES = 2.0

    /** A new measured shop is in-band (worth folding) only above the noise floors. */
    fun isValidSample(items: Int, minutes: Double): Boolean = items >= MIN_ITEMS && minutes >= MIN_MINUTES

    /**
     * Fold one measured shop ([items] over [minutes] in-store) into the running mean items/min and
     * sample count. Out-of-band samples are ignored (returns the prior pair unchanged). The mean is
     * incremental — `avg + (rate - avg) / n` — so it's stable and independent of fold order.
     */
    fun fold(prevAvg: Double?, prevN: Int, items: Int, minutes: Double): Pair<Double?, Int> {
        if (!isValidSample(items, minutes)) return prevAvg to prevN
        val rate = items / minutes
        val n = (prevN.coerceAtLeast(0)) + 1
        val avg = if (prevAvg == null || prevN <= 0) rate else prevAvg + (rate - prevAvg) / n
        return avg to n
    }
}
