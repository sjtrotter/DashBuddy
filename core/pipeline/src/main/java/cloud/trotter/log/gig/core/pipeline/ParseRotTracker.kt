package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.core.pipeline.rules.ParsedFieldsFactory
import cloud.trotter.log.gig.domain.pipeline.ParseRotCause
import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import java.util.TreeMap

/** A rule's parse rot crossed the trip line ([ParseRotTracker]), or a rotted rule read healthy again (#1560). */
sealed interface RotEdge {
    val platformWire: String

    data class Tripped(override val platformWire: String, val cause: ParseRotCause) : RotEdge
    data class Cleared(override val platformWire: String, val ruleId: String) : RotEdge
}

/**
 * The pure half of the #1560 parse-rot cause: a rule that keeps MATCHING screens while its
 * shape-required fields (`ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE`: offer → payAmount,
 * distance; post_task → totalPay; session_ended → totalEarnings) come back null. #1036 counts
 * those per rule ([ParseShortfall]); this decides when the count is an incident.
 *
 * No Android, no logging, no time source: every time is the frame's own `obs.timestamp`, and
 * [record] / [sweep] just return the edges, so the side effects live in
 * [RecognitionHealthMonitor]. Not thread-safe on its own; the monitor owns the lock.
 *
 * **Frames.** The caller feeds the post-`FrameGate` admissions the #937 sample uses
 * ([RecognitionHealth]), screens only.
 *
 * **Occurrences, one per visit.** Rot is counted in occurrences, not frames. A rotted offer that
 * falls to UNKNOWN is admitted once per countdown tick, because UNKNOWN frames dedup by content
 * hash, so counting frames would trip on one card. An occurrence of rule R starts only on a rot
 * frame whose platform's previous admitted frame carried no eligible rot of R (a new visit), and
 * only when R's previous occurrence started at least [PRE_RENDER_WINDOW_MS] earlier, so a dasher
 * flicking between one rotted card and another screen makes one occurrence, not five.
 *
 * **Pre-render.** An occurrence confirms when no healthy parse of R arrives within
 * [PRE_RENDER_WINDOW_MS] of frame time after its start; a healthy R inside the window discards
 * it as a pre-render frame (a screen drawn before its values land). The definition of a
 * pre-render frame lives here and only here; later consumers of eligible attempts reuse it.
 * R trips after [TRIP_OCCURRENCES] confirmed occurrences with no healthy parse of R between, and
 * [CLEAR_HEALTHY] healthy parses of R clear it.
 *
 * **Only required-field rot counts.** Total rot of shapes with no required fields is deferred to
 * #1049: those shapes have benign nulls (the `waiting_for_offer` carousel), and sensitive and
 * noise shapes declare no required fields at all.
 *
 * **Skipped-branch shortfalls count.** DoorDash offer branches carry `fieldNotNull` → `Skip` on
 * payAmount, distance and timeToCompleteMinutes (`matchers/rules/doordash/offer.json5:352,712,
 * 717,1048,1053,1058`), so offer rot leaves the frame UNKNOWN and the skipped branch's shortfall
 * is the only trace; no receipt or summary rule carries one. The exception is a healthy winner:
 * a frame whose winning rule parsed cleanly can still carry a shortfall from a skipped branch of
 * the same rule, and only the winning branch's own shortfall ([ParseShortfall.branchSkipped]
 * false) says the winner rotted.
 *
 * **Known limits.**
 *  - *Burst cap.* The UNKNOWN offer frames pass `UnknownSuppressor` (`FrameGate`, 200 frames per
 *    burst, re-armed after a 30-minute quiet gap), so once a burst has spent the cap, offer rot is
 *    not seen until the gap. That fails quiet, never false; receipt and summary rot are
 *    recognized frames and are not affected.
 *  - *Confirmation needs a frame.* An occurrence confirms on a later admitted frame of the same
 *    platform (or a [sweep]); a dasher parked on the fifth visit trips on the next frame.
 *  - *Sibling rules.* A run resets only on the same rule id, so a screen pair split across two
 *    rules (`doordash.screen.delivery_summary_collapsed` / `_expanded`, `dropoff.json5:2140,1903`)
 *    resets neither from the other. That is right for a real break and a false trip if one of the
 *    pair legitimately shows no pay; the corpus and full-dash tests and the field checklist are
 *    the check.
 */
class ParseRotTracker {

    /**
     * One rule's run on one platform.
     *
     * Invariant: at most one occurrence is pending per rule. A new start needs
     * `t - lastOccurrenceStart >= PRE_RENDER_WINDOW_MS`, and [pendingAt], when set, equals
     * [lastOccurrenceStart], so step 1 of [record] (the sweep) has always confirmed it before
     * step 3 can start the next one.
     */
    private class RuleRun(var shape: String) {
        /** Start of the one unconfirmed occurrence. */
        var pendingAt: Long? = null

        /** Start of the rule's latest occurrence, confirmed or not. */
        var lastOccurrenceStart: Long? = null
        var confirmed = 0

        /** Null required fields, the union over the run. */
        val nullFields = LinkedHashSet<String>()
        var rotted = false
        var healthyStreak = 0
    }

    private class PlatformState {
        /** Rule ids with eligible rot on the platform's previous admitted frame; [sweep] never changes it. */
        var rotOnLastFrame: Set<String> = emptySet()

        /** Sorted by rule id, so edges come out in a stable order. */
        val rules = TreeMap<String, RuleRun>()
    }

    private val platforms = HashMap<String, PlatformState>()

    /**
     * One admitted screen frame.
     *
     * @param winnerRuleId the rule that classified the frame; null for an UNKNOWN frame.
     * @param shortfalls the frame's own shortfalls, as the classifier reported them.
     * @return the edges, every [RotEdge.Tripped] before every [RotEdge.Cleared].
     */
    fun record(
        platformWire: String,
        timestamp: Long,
        winnerRuleId: String?,
        shortfalls: List<ParseShortfall>,
    ): List<RotEdge> {
        val state = platforms.getOrPut(platformWire) { PlatformState() }

        // 1. Confirm what this frame's time has made old enough.
        val edges = sweepState(platformWire, state, timestamp)

        // 2. A healthy winner: its own branch read every required field.
        val healthy = winnerRuleId != null && shortfalls.none {
            it.ruleId == winnerRuleId && !it.branchSkipped && it.hasParseTrigger
        }
        if (healthy) {
            val winner = checkNotNull(winnerRuleId)
            state.rules[winner]?.let { run ->
                // A pending occurrence was a pre-render frame; forgetting its start keeps a discarded
                // start from muting the next real visit.
                run.pendingAt = null
                run.lastOccurrenceStart = null
                if (!run.rotted) {
                    state.rules.remove(winner)
                } else {
                    run.healthyStreak++
                    if (run.healthyStreak >= CLEAR_HEALTHY) {
                        edges += RotEdge.Cleared(platformWire, winner)
                        state.rules.remove(winner)
                    }
                }
            }
        }

        // 3. Rot on this frame.
        val eligibleRot = shortfalls.filter(::eligible).filterNot { healthy && it.ruleId == winnerRuleId }
        val rotNow = LinkedHashSet<String>()
        for (shortfall in eligibleRot) {
            val ruleId = shortfall.ruleId
            val shape = checkNotNull(shortfall.shape)
            val run = state.rules.getOrPut(ruleId) { RuleRun(shape) }
            run.shape = shape
            run.nullFields += shortfall.nullRequiredFields
            if (!rotNow.add(ruleId)) continue
            val last = run.lastOccurrenceStart
            if (ruleId !in state.rotOnLastFrame && (last == null || timestamp - last >= PRE_RENDER_WINDOW_MS)) {
                run.pendingAt = timestamp
                run.lastOccurrenceStart = timestamp
            }
        }

        // 4. The next frame's visit test reads this frame's rot.
        state.rotOnLastFrame = rotNow

        // 5. Trips before clears (L8): a frame that trips one rule and clears another keeps the incident open.
        return ordered(edges)
    }

    /** Confirmation only, no frame: confirms what [timestamp] has made old enough. Tests end each platform with it. */
    fun sweep(platformWire: String, timestamp: Long): List<RotEdge> {
        val state = platforms[platformWire] ?: return emptyList()
        return ordered(sweepState(platformWire, state, timestamp))
    }

    fun isRotted(platformWire: String, ruleId: String): Boolean =
        platforms[platformWire]?.rules?.get(ruleId)?.rotted == true

    /** Confirmed occurrences in the rule's current run (tests). */
    fun confirmedOccurrences(platformWire: String, ruleId: String): Int =
        platforms[platformWire]?.rules?.get(ruleId)?.confirmed ?: 0

    private fun sweepState(platformWire: String, state: PlatformState, timestamp: Long): MutableList<RotEdge> {
        val edges = mutableListOf<RotEdge>()
        for ((ruleId, run) in state.rules) {
            val pending = run.pendingAt ?: continue
            if (pending + PRE_RENDER_WINDOW_MS > timestamp) continue
            run.pendingAt = null
            if (run.rotted) {
                // A confirmed rot visit of a rotted rule: the clear streak starts over.
                run.healthyStreak = 0
                continue
            }
            run.confirmed++
            if (run.confirmed >= TRIP_OCCURRENCES) {
                run.rotted = true
                run.confirmed = 0
                run.healthyStreak = 0
                edges += RotEdge.Tripped(platformWire, causeOf(ruleId, run, timestamp))
            }
        }
        return edges
    }

    private fun causeOf(ruleId: String, run: RuleRun, timestamp: Long): ParseRotCause {
        val required = ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE[run.shape].orEmpty()
        return ParseRotCause(
            ruleId = ruleId,
            shape = run.shape,
            nullFields = required.filter { it in run.nullFields },
            trippedAt = timestamp,
        )
    }

    private fun ordered(edges: List<RotEdge>): List<RotEdge> =
        edges.filterIsInstance<RotEdge.Tripped>() + edges.filterIsInstance<RotEdge.Cleared>()

    /** Required-field rot of a shape that declares required fields; nothing else counts. */
    private fun eligible(shortfall: ParseShortfall): Boolean {
        val shape = shortfall.shape ?: return false
        return shortfall.hasParseTrigger &&
            ParsedFieldsFactory.REQUIRED_FIELDS_BY_SHAPE[shape].orEmpty().isNotEmpty() &&
            shortfall.nullRequiredFields.isNotEmpty()
    }

    companion object {
        /** Confirmed rot occurrences of one rule, with no healthy parse between, that trip it. */
        const val TRIP_OCCURRENCES = 5

        /** Healthy parses of the affected rule that clear it. */
        const val CLEAR_HEALTHY = 3

        /** Pre-render discard window, and the minimum spacing of one rule's occurrence starts (frame time). */
        const val PRE_RENDER_WINDOW_MS = 3_000L
    }
}
