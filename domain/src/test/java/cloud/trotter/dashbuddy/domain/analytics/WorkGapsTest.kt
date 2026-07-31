package cloud.trotter.dashbuddy.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #983 / brief §7.8 — the pure between-job gap fold.
 *
 * The interesting properties are all about what does NOT count as a gap: a span across two dashes, the
 * tail after a dash's last drop, a pairing that escapes its own session's end, an out-of-order stamp.
 * Each of those, counted, would invent waiting time the driver never had — and the whole point of the
 * card is that the number is measured.
 *
 * The ordering property is the #732 contract: "which accept came next" is a `sequenceId` question, so
 * a fold that sorted by timestamp would pick a different accept whenever `occurredAt` lagged.
 */
class WorkGapsTest {

    private val minute = 60_000L
    private val hour = 60 * minute
    private val base = 1_600_000_000_000L

    private fun drop(session: String, seq: Long, at: Long) = SessionEvent(session, seq, at)
    private fun accept(session: String, seq: Long, at: Long) = SessionEvent(session, seq, at)

    // ── the happy path ──────────────────────────────────────────────────

    @Test
    fun `a completion followed by an accept in the same dash is a gap`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("A", 10, base)),
            accepts = listOf(accept("A", 11, base + 8 * minute)),
            sessionEndMillis = mapOf("A" to base + hour),
        )

        assertEquals(1, stats.count)
        assertEquals(8 * minute, stats.totalMillis)
        assertEquals(8 * minute, stats.medianMillis)
        assertEquals(8 * minute, stats.p90Millis)
        assertEquals(8 * minute, stats.longestMillis)
        assertEquals(0, stats.completionsWithoutGap)
        assertTrue(stats.hasGaps)
    }

    @Test
    fun `the gap runs to the NEXT accept, not a later one`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("A", 10, base)),
            accepts = listOf(
                accept("A", 11, base + 5 * minute),
                accept("A", 20, base + 40 * minute),
            ),
            sessionEndMillis = mapOf("A" to base + 2 * hour),
        )

        assertEquals(1, stats.count)
        assertEquals(5 * minute, stats.totalMillis)
    }

    /** #732: sequence is the order key; a lagging timestamp must not re-pick which accept came next. */
    @Test
    fun `next accept is chosen by sequenceId, not by timestamp`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("A", 10, base)),
            accepts = listOf(
                // Listed out of order AND stamped out of order: seq 11 is the next accept even though
                // seq 30 carries an earlier timestamp.
                accept("A", 30, base + 3 * minute),
                accept("A", 11, base + 9 * minute),
            ),
            sessionEndMillis = mapOf("A" to base + 2 * hour),
        )

        assertEquals(1, stats.count)
        assertEquals(9 * minute, stats.totalMillis)
    }

    // ── what is NOT a gap ───────────────────────────────────────────────

    @Test
    fun `a gap never spans two dashes`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("MON", 10, base)),
            // Next day's dash, next accept — 20 hours later, and emphatically not a gap.
            accepts = listOf(accept("TUE", 11, base + 20 * hour)),
            sessionEndMillis = mapOf("MON" to base + hour, "TUE" to base + 21 * hour),
        )

        assertEquals(0, stats.count)
        assertEquals(0L, stats.totalMillis)
        assertEquals(1, stats.completionsWithoutGap)
        assertFalse(stats.hasGaps)
        assertNull(stats.medianMillis)
    }

    @Test
    fun `the last drop of a dash is a tail, not a gap`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("A", 10, base), drop("A", 30, base + 30 * minute)),
            accepts = listOf(accept("A", 11, base + 6 * minute)),
            sessionEndMillis = mapOf("A" to base + hour),
        )

        assertEquals(1, stats.count)               // only the first drop paired
        assertEquals(6 * minute, stats.totalMillis)
        assertEquals(1, stats.completionsWithoutGap) // the tail, counted as coverage
        assertEquals(2, stats.completionsConsidered)
    }

    @Test
    fun `an accept past its own dash's end is dropped, not measured`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("A", 10, base)),
            accepts = listOf(accept("A", 11, base + 3 * hour)),   // after the dash ended
            sessionEndMillis = mapOf("A" to base + hour),
        )

        assertEquals(0, stats.count)
        assertEquals(1, stats.completionsWithoutGap)
    }

    @Test
    fun `a session with no recorded end is simply unbounded`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("A", 10, base)),
            accepts = listOf(accept("A", 11, base + 3 * hour)),
            sessionEndMillis = emptyMap(),
        )

        assertEquals(1, stats.count)
        assertEquals(3 * hour, stats.totalMillis)
    }

    @Test
    fun `a non-positive span is a stamp anomaly and is not measured`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("A", 10, base + 5 * minute)),
            accepts = listOf(accept("A", 11, base)),   // earlier than the drop it follows in sequence
            sessionEndMillis = mapOf("A" to base + hour),
        )

        assertEquals(0, stats.count)
        assertEquals(1, stats.completionsWithoutGap)
    }

    @Test
    fun `no completions at all is empty, not a fabricated zero-gap`() {
        val stats = WorkGaps.compute(
            completions = emptyList(),
            accepts = listOf(accept("A", 11, base)),
            sessionEndMillis = mapOf("A" to base + hour),
        )

        assertEquals(GapStats.EMPTY, stats)
    }

    // ── the distribution ────────────────────────────────────────────────

    @Test
    fun `median and p90 are nearest-rank over every measured gap`() {
        // Ten gaps of 1..10 minutes, laid out across one dash.
        val completions = (0 until 10).map { drop("A", 10L + it * 10, base + it * hour) }
        val accepts = (0 until 10).map { accept("A", 11L + it * 10, base + it * hour + (it + 1) * minute) }

        val stats = WorkGaps.compute(completions, accepts, mapOf("A" to base + 24 * hour))

        assertEquals(10, stats.count)
        assertEquals((1..10).sumOf { it * minute }, stats.totalMillis)
        assertEquals(5 * minute, stats.medianMillis)   // ceil(0.5 × 10) = 5th value
        assertEquals(9 * minute, stats.p90Millis)      // ceil(0.9 × 10) = 9th value
        assertEquals(10 * minute, stats.longestMillis)
        assertEquals(0, stats.longGapCount)
    }

    /** A long gap is COUNTED (it is real unpaid online time) and reported, never silently dropped. */
    @Test
    fun `long gaps stay in the totals and are counted separately`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("A", 10, base), drop("A", 30, base + 5 * hour)),
            accepts = listOf(
                accept("A", 11, base + 10 * minute),
                accept("A", 31, base + 5 * hour + WorkGaps.LONG_GAP_MILLIS),
            ),
            sessionEndMillis = mapOf("A" to base + 12 * hour),
        )

        assertEquals(2, stats.count)
        assertEquals(10 * minute + WorkGaps.LONG_GAP_MILLIS, stats.totalMillis)
        assertEquals(1, stats.longGapCount)
        assertEquals(WorkGaps.LONG_GAP_MILLIS, stats.longestMillis)
    }

    @Test
    fun `gaps from separate dashes are all measured, each inside its own`() {
        val stats = WorkGaps.compute(
            completions = listOf(drop("A", 10, base), drop("B", 50, base + 10 * hour)),
            accepts = listOf(
                accept("A", 11, base + 4 * minute),
                accept("B", 51, base + 10 * hour + 12 * minute),
            ),
            sessionEndMillis = mapOf("A" to base + hour, "B" to base + 12 * hour),
        )

        assertEquals(2, stats.count)
        assertEquals(16 * minute, stats.totalMillis)
        assertEquals(4 * minute, stats.medianMillis)   // ceil(0.5 × 2) = 1st value
    }
}
