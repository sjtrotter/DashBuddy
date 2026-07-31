package cloud.trotter.dashbuddy.domain.analytics

/**
 * One session-scoped record row reduced to what a gap measurement needs (#983 / brief §7.8): which
 * dash it belongs to, its **fold order**, and its own domain timestamp.
 *
 * [sequenceId] and [timestamp] are deliberately separate fields, because the `app_events` ordering
 * contract (#732) says they can disagree: `sequenceId` is the authoritative order key while
 * `occurredAt` may lag it, so "which accept came next" is a SEQUENCE question and "how long was the
 * gap" is a TIMESTAMP question. The read side supplies each row's own domain timestamp — a delivery's
 * `completedAt`, an offer's `decidedAt` — never the event's `occurredAt`.
 */
data class SessionEvent(
    val sessionId: String,
    /** The source event's `sequenceId` (the read-model row PK) — the authoritative ordering key (#732). */
    val sequenceId: Long,
    /** The row's own DOMAIN timestamp (`completedAt` / `decidedAt`), which is what a duration is measured from. */
    val timestamp: Long,
)

/**
 * The window's **between-job gaps** (#983 / brief §7.8) — how long the driver sat between finishing a
 * delivery and taking the next offer, measured over the window's own dashes.
 *
 * Every figure is MEASURED wall-clock time from the read model; nothing is estimated or smoothed.
 * [medianMillis] is the headline because it is robust to the one 90-minute lull that would drag a
 * mean; [p90Millis] is the "bad day" figure beside it.
 *
 * **Coverage is a field, not a footnote** (§9): [completionsWithoutGap] counts the drops that produced
 * no measurable gap at all — the last drop of a dash (its wait ran into the dash ending, which is a
 * tail, not a gap), and any pair whose stamps were incoherent. A surface that quotes a median must be
 * able to say how much of the window it covers.
 *
 * **Long gaps are STATED, never dropped.** [longGapCount] counts gaps at or over
 * [WorkGaps.LONG_GAP_MILLIS], and they remain in [totalMillis] and in the percentiles, because they
 * are real online minutes that earned nothing. What we genuinely cannot know is *why* — the
 * running-hourly-rate doc §3 names break-vs-dry-market as an unresolvable ambiguity on-device — so the
 * honest move is to count the time and let the surface say a break may be in there.
 */
data class GapStats(
    /** How many gaps were measured. */
    val count: Int,
    /** Σ of every measured gap — the "waiting" input to [HourComposition]. */
    val totalMillis: Long,
    /** Median gap, nearest-rank; null when nothing was measured. */
    val medianMillis: Long?,
    /** 90th-percentile gap, nearest-rank; null when nothing was measured. */
    val p90Millis: Long?,
    /** The single longest measured gap; null when nothing was measured. */
    val longestMillis: Long?,
    /** Measured gaps at or over [WorkGaps.LONG_GAP_MILLIS] — included above, stated separately. */
    val longGapCount: Int,
    /** Drops that produced no measurable gap (dash tail, or incoherent stamps) — the coverage figure. */
    val completionsWithoutGap: Int,
) {
    /** True when at least one gap was measured — the surface renders its empty state otherwise. */
    val hasGaps: Boolean get() = count > 0

    /** Drops considered = the ones that yielded a gap plus the ones that could not. */
    val completionsConsidered: Int get() = count + completionsWithoutGap

    companion object {
        val EMPTY = GapStats(
            count = 0,
            totalMillis = 0L,
            medianMillis = null,
            p90Millis = null,
            longestMillis = null,
            longGapCount = 0,
            completionsWithoutGap = 0,
        )
    }
}

/**
 * The pure §7.8 gap fold (#983): pair each completed delivery with the next offer the driver accepted
 * **in the same dash**, and measure the wall-clock time between them.
 *
 * Rules, all of them fail-null (a gap we cannot defend is not reported):
 *
 * 1. **Never across dashes or days.** A gap only exists between two rows of the SAME `sessionId`.
 *    Everything else — the overnight span between Tuesday's last drop and Wednesday's first accept —
 *    is not a gap in anyone's language, so a session-less row (the "(No session)" bucket) contributes
 *    nothing at all: it has no dash to be inside.
 * 2. **Order by sequence, measure by timestamp** (#732). "The next accept" is the first accept with a
 *    greater `sequenceId`; the duration is `accept.timestamp − completion.timestamp`. Ordering by the
 *    timestamps would put the fold at the mercy of exactly the lag the contract warns about.
 * 3. **The tail is not a gap.** A drop with no following accept in its dash was followed by the dash
 *    ENDING; treating "last drop → session end" as a gap would invent a waiting period out of the
 *    driver's decision to stop. It counts toward [GapStats.completionsWithoutGap] instead.
 * 4. **Bounded by the dash.** A pairing whose accept lands after the session's own effective end is
 *    incoherent (a stale id, a mis-stamped row), so it is dropped rather than measured — the same
 *    fail-null-beats-fail-wrong rule (#745) the rest of the read model runs on.
 * 5. **Non-positive durations are dropped.** A zero/negative span is a stamp anomaly, not an instant
 *    hand-off.
 *
 * Pure and deterministic: same rows in, same stats out, no clock read (Principle 1).
 */
object WorkGaps {

    /**
     * At or above this, a gap is *reported* as possibly-a-break (2h).
     *
     * Deliberately a REPORTING threshold, not an exclusion: the running-hourly-rate doc §3 lists
     * "break vs. dry-market disambiguation" as something we cannot resolve on-device without an
     * explicit break signal, so dropping long gaps would quietly delete real unpaid online time,
     * while keeping them silently would let one lunch redefine a "typical" hour. Counting them and
     * saying so is the only honest option (§9).
     */
    const val LONG_GAP_MILLIS = 2L * 60L * 60L * 1_000L

    /**
     * Fold [completions] and [accepts] into the window's [GapStats].
     *
     * @param completions one entry per completed delivery in the window's dashes (`completedAt`).
     * @param accepts one entry per accepted offer in the window's dashes (`decidedAt`).
     * @param sessionEndMillis each dash's effective end (`endedAt`, else its last observed event) —
     *   the rule-4 bound. A session absent from the map is simply unbounded.
     */
    fun compute(
        completions: List<SessionEvent>,
        accepts: List<SessionEvent>,
        sessionEndMillis: Map<String, Long>,
    ): GapStats {
        if (completions.isEmpty()) return GapStats.EMPTY

        val acceptsBySession = accepts
            .groupBy { it.sessionId }
            .mapValues { (_, rows) -> rows.sortedBy { it.sequenceId } }

        val durations = ArrayList<Long>(completions.size)
        var withoutGap = 0

        completions.groupBy { it.sessionId }.forEach { (sessionId, drops) ->
            val sessionAccepts = acceptsBySession[sessionId].orEmpty()
            val bound = sessionEndMillis[sessionId]
            drops.forEach { drop ->
                // Rule 2: the NEXT accept is a sequence fact, not a timestamp one (#732).
                val next = sessionAccepts.firstOrNull { it.sequenceId > drop.sequenceId }
                val duration = when {
                    next == null -> null                                        // rule 3 — tail
                    bound != null && next.timestamp > bound -> null             // rule 4 — out of its dash
                    else -> (next.timestamp - drop.timestamp).takeIf { it > 0 } // rule 5
                }
                if (duration == null) withoutGap++ else durations += duration
            }
        }

        if (durations.isEmpty()) {
            return GapStats.EMPTY.copy(completionsWithoutGap = withoutGap)
        }
        val sorted = durations.sorted()
        return GapStats(
            count = sorted.size,
            totalMillis = sorted.sum(),
            medianMillis = Percentiles.nearestRank(sorted, 0.50),
            p90Millis = Percentiles.nearestRank(sorted, 0.90),
            longestMillis = sorted.last(),
            longGapCount = sorted.count { it >= LONG_GAP_MILLIS },
            completionsWithoutGap = withoutGap,
        )
    }
}
