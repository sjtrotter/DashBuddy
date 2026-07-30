package cloud.trotter.dashbuddy.domain.analytics

/**
 * One **accepted** offer of the window paired with what its linked job actually produced (#975 /
 * brief §7.5) — the raw input behind [EstimateVsReality]. Read off the `offer_records` ⟕
 * `delivery_records` join on `linkedJobId = jobId`; every field is nullable exactly where the data
 * genuinely may not exist, so the whole population policy can live in one pure place
 * ([EstimateVsReality.of]) instead of being split between SQL predicates and Kotlin.
 *
 * The row set is deliberately **unfiltered beyond the window + outcome**: an offer with no estimate,
 * no linked job, or no completed delivery is still emitted, because it is part of the *denominator*
 * the surface has to state ("across N of M accepted offers"). Dropping it in SQL would leave the
 * read unable to say how much of the window it actually describes.
 */
data class AcceptedOfferOutcomeSample(
    /** The offer record's PK — its source event's `sequenceId` (provenance; also the dedupe key). */
    val offerEventSequenceId: Long,
    /**
     * The offer's FROZEN decision-time `estDollarsPerHour`. Null on a #936 no-verdict evaluation
     * (the evaluator now fails closed on a missing distance rather than inventing a one-mile trip),
     * and on any offer whose evaluation never landed.
     */
    val estPerHour: Double?,
    /** The job this offer was linked to by store resolution (#159 F4); null when it was never linked. */
    val linkedJobId: String?,
    /**
     * Σ **frozen** `netProfit` over that job's deliveries — nullable and deliberately un-`COALESCE`d:
     * SQL `SUM` of an all-null set is NULL, the honest "nothing realized was recorded" signal rather
     * than a fabricated `$0.00` that would drag the realized mean toward zero.
     */
    val realizedNet: Double?,
    /** Σ driver-entered `cashTip` over the same deliveries (#688 keeps cash outside `netProfit`). */
    val realizedCashTip: Double,
    /** Σ measured `realizedMinutes` over the same deliveries; null when none was ever measured. */
    val realizedMinutes: Double?,
)

/**
 * "Estimate vs reality" for the window (#975 / brief §7.5): what the evaluator projected at decision
 * time for the offers the driver **accepted**, next to what those jobs actually paid per realized
 * hour — plus the plain ratio between them (`accepted offers realized ~89% of their estimate`).
 *
 * ## The population, exactly
 *
 * The denominator [acceptedOffers] is every ACCEPTED offer record in the window **excluding
 * resolved orphans** (#810 B2 — an accepted offer the projector or the driver established was
 * invisibly unassigned never produced a delivery, so counting it would drag realized net toward zero
 * for a job that was never worked). That exclusion is a `outcomeResolved IS NULL` predicate on the
 * read, byte-identical to the one `offerOutcomes` already carries, so this denominator and the
 * funnel's `accepted` count describe the same set by construction (Principle 5).
 *
 * From that denominator, an offer is **included** in [offers] — the comparison's actual sample —
 * only when all four hold:
 *  1. **It carries a frozen estimate** (`estPerHour != null`). #936 made the evaluator fail CLOSED
 *     on a missing distance, so a no-verdict offer freezes null economics rather than a fabricated
 *     one-mile figure; including it as a zero would drag the estimate mean down and *flatter* the
 *     realized side. Excluded, and counted only in the denominator.
 *  2. **It was linked to a job** (`linkedJobId != null`). Without the link there is no realized side
 *     to compare against at all.
 *  3. **It is the SOLE accepted offer on that job.** A stacked job settles as ONE set of receipts
 *     that cannot be split per offer, so attributing the whole job's net to each of its offers would
 *     count the same dollars twice and roughly double the realized bar. Both offers are dropped —
 *     fail-null beats fail-wrong (#745), and the denominator still states the loss.
 *  4. **Its job produced measured realized economics** (`realizedNet != null` and
 *     `realizedMinutes > 0`). A job with no recorded net or no measured time yields no rate; a zero
 *     denominator is not a rate, it is a missing measurement (the `NetProfit` discipline).
 *
 * ## The aggregate, exactly
 *
 * Both bars are a **mean of per-offer rates**, one vote per accepted decision — not a time-weighted
 * Σnet ÷ Σhours. That is deliberate: the question this card answers is *"when I accept an offer at
 * an estimated $X/hr, what do I actually get?"*, which is a per-decision question, so per-decision
 * weighting is the honest one. It also keeps the estimate side a straight aggregate of the frozen
 * `estDollarsPerHour` column — the one owner of an offer's decision-time rate — instead of
 * re-deriving it from `estNetPay ÷ estTimeMinutes`, which would be a second copy of that math
 * waiting to drift (Principle 5).
 *
 * Realized rate per offer = `(Σ netProfit + Σ cashTip) ÷ (Σ realizedMinutes ÷ 60)`. Cash tips are
 * additive at the read site, exactly as everywhere else (#688).
 *
 * Every figure on the estimate side is FROZEN — an economy edit never re-costs a past offer — and
 * every figure on the realized side is the frozen per-delivery net. Nothing here is recomputed.
 */
data class EstimateVsReality(
    /** Accepted, non-orphan-resolved offers in the window — the population denominator. */
    val acceptedOffers: Int,
    /** How many of them satisfied all four inclusion rules above — the comparison's sample size. */
    val offers: Int,
    /** Mean frozen est. $/hr over [offers]; null when [offers] is zero. */
    val estPerHour: Double?,
    /** Mean realized net $/hr over the same [offers]; null when [offers] is zero. */
    val realizedPerHour: Double?,
    /**
     * [realizedPerHour] ÷ [estPerHour] — "realized ~N% of the estimate". Null when there is no
     * sample or the estimate mean is not positive (a ratio against a non-positive denominator is
     * not a fact).
     */
    val ratio: Double?,
) {

    /** True when both bars have a real value to draw — the only state in which the card renders them. */
    val hasComparison: Boolean get() = offers > 0 && estPerHour != null && realizedPerHour != null

    /**
     * True when the comparison rests on fewer than [MIN_CONFIDENT_OFFERS] matched offers. The card
     * still renders (hiding data the driver owns is its own dishonesty) but must SAY the sample is
     * thin — §9: thin data is stated, never smoothed over or hidden.
     */
    val isThinData: Boolean get() = offers in 1 until MIN_CONFIDENT_OFFERS

    /** True when some accepted offers of the window could not be matched — the coverage caveat. */
    val hasUnmatchedOffers: Boolean get() = acceptedOffers > offers

    companion object {

        /**
         * Below this many matched offers the comparison is a hint, not a measurement. Deliberately
         * modest: the driver's own handful of dashes is the only data there will ever be, so the bar
         * for *showing* it stays low while the bar for presenting it as settled stays honest.
         */
        const val MIN_CONFIDENT_OFFERS = 5

        val EMPTY = EstimateVsReality(
            acceptedOffers = 0,
            offers = 0,
            estPerHour = null,
            realizedPerHour = null,
            ratio = null,
        )

        /**
         * Apply the population rules documented on the class to [samples] (every accepted, non-
         * orphan-resolved offer of the window) and fold the survivors into the two means + the ratio.
         */
        fun of(samples: List<AcceptedOfferOutcomeSample>): EstimateVsReality {
            if (samples.isEmpty()) return EMPTY

            // Rule 3 — a jobId claimed by two or more accepted offers is a STACK: its receipts can't
            // be split per offer, so every offer on it is dropped rather than each claiming the whole.
            val offersPerJob = samples.mapNotNull { it.linkedJobId }.groupingBy { it }.eachCount()

            /** One included offer's two rates — the est. the verdict froze, and what it realized. */
            data class Matched(val est: Double, val realized: Double)

            val matched = samples.mapNotNull { sample ->
                val jobId = sample.linkedJobId ?: return@mapNotNull null          // rule 2
                if ((offersPerJob[jobId] ?: 0) > 1) return@mapNotNull null        // rule 3
                val est = sample.estPerHour ?: return@mapNotNull null             // rule 1 (#936)
                val net = sample.realizedNet ?: return@mapNotNull null            // rule 4
                val minutes = sample.realizedMinutes ?: return@mapNotNull null    // rule 4
                if (minutes <= 0.0) return@mapNotNull null                        // rule 4
                Matched(est = est, realized = (net + sample.realizedCashTip) / (minutes / MINUTES_PER_HOUR))
            }

            if (matched.isEmpty()) return EMPTY.copy(acceptedOffers = samples.size)

            val estMean = matched.sumOf { it.est } / matched.size
            val realizedMean = matched.sumOf { it.realized } / matched.size
            return EstimateVsReality(
                acceptedOffers = samples.size,
                offers = matched.size,
                estPerHour = estMean,
                realizedPerHour = realizedMean,
                ratio = if (estMean > 0.0) realizedMean / estMean else null,
            )
        }

        private const val MINUTES_PER_HOUR = 60.0
    }
}
