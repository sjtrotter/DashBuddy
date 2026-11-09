package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.domain.pipeline.ParseRotCause
import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1560 — the pure parse-rot decision: occurrences per visit, the pre-render window, the spacing of
 * occurrence starts, the trip and clear counts, and trips before clears.
 *
 * Fixture vocabulary: a *rot frame* of rule X is an UNKNOWN frame carrying a skipped offer branch's
 * shortfall of X (the DoorDash offer shape: rot leaves the frame UNKNOWN). A *healthy frame* of X is
 * one X won with no shortfall. A *separator* is any admitted frame without rot of the rule under test.
 */
class ParseRotTrackerTest {

    private val dd = "doordash"
    private val uber = "uber"
    private val ruleA = "doordash.screen.offer_card"
    private val ruleB = "doordash.screen.delivery_receipt"
    private val offerFields = listOf("payAmount", "distance")

    private fun offerRot(rule: String = ruleA, fields: List<String> = offerFields) =
        ParseShortfall(ruleId = rule, shape = "offer", nullRequiredFields = fields, branchSkipped = true)

    private fun ParseRotTracker.rot(t: Long, rule: String = ruleA, wire: String = dd): List<RotEdge> =
        record(wire, t, null, listOf(offerRot(rule)))

    private fun ParseRotTracker.healthy(t: Long, rule: String, wire: String = dd): List<RotEdge> =
        record(wire, t, rule, emptyList())

    private fun ParseRotTracker.separator(t: Long, wire: String = dd): List<RotEdge> =
        record(wire, t, null, emptyList())

    private fun ParseRotTracker.confirmed(rule: String = ruleA, wire: String = dd) = confirmedOccurrences(wire, rule)

    /** Five rot visits of A, starts 4 s apart from [from], each followed by a healthy B 1 s later. */
    private fun ParseRotTracker.fiveVisits(from: Long = 0L): List<RotEdge> =
        (0 until 5).flatMap { i ->
            val t = from + i * 4_000L
            rot(t) + healthy(t + 1_000, ruleB)
        }

    /** A tripped by case 1's sequence; the trip lands on `sweep(20 000)`. */
    private fun trippedA(): ParseRotTracker = ParseRotTracker().apply {
        fiveVisits()
        assertEquals(1, sweep(dd, 20_000).count { it is RotEdge.Tripped })
        assertTrue(isRotted(dd, ruleA))
    }

    // 1
    @Test
    fun `five visits trip`() {
        val tracker = ParseRotTracker()

        assertTrue("no record edge before the fifth confirms", tracker.fiveVisits().isEmpty())

        assertEquals(
            listOf(RotEdge.Tripped(dd, ParseRotCause(ruleA, "offer", offerFields, trippedAt = 20_000))),
            tracker.sweep(dd, 20_000),
        )
    }

    // 2
    @Test
    fun `one visit is one occurrence`() {
        val fast = ParseRotTracker()
        val fastEdges = (0 until 30).flatMap { fast.rot(it * 300L) } + fast.sweep(dd, 20_000)
        assertEquals(1, fast.confirmed())
        assertTrue(fastEdges.none { it is RotEdge.Tripped })

        val slow = ParseRotTracker()
        val slowEdges = (0 until 30).flatMap { slow.rot(it * 1_000L) } + slow.sweep(dd, 60_000)
        assertEquals(1, slow.confirmed())
        assertTrue(slowEdges.none { it is RotEdge.Tripped })
    }

    // 3
    @Test
    fun `a visit that turns healthy within the window is pre-render`() {
        val early = ParseRotTracker()
        early.rot(0); early.rot(100); early.rot(200)
        early.healthy(900, ruleA)
        early.sweep(dd, 10_000)
        assertEquals(0, early.confirmed())

        val late = ParseRotTracker()
        late.rot(0); late.rot(100); late.rot(200)
        assertTrue(late.sweep(dd, 3_000).isEmpty())
        assertEquals("the occurrence confirmed first", 1, late.confirmed())
        assertTrue(late.healthy(3_000, ruleA).isEmpty())
        assertEquals("then the healthy parse reset the run", 0, late.confirmed())
    }

    // 4
    @Test
    fun `a healthy parse resets the run`() {
        val tracker = ParseRotTracker()
        val edges = mutableListOf<RotEdge>()
        for (i in 0 until 4) {
            val t = i * 4_000L
            edges += tracker.rot(t)
            edges += tracker.separator(t + 1_000)
        }
        edges += tracker.sweep(dd, 15_000)
        assertEquals(4, tracker.confirmed())

        edges += tracker.healthy(15_000, ruleA)
        assertEquals(0, tracker.confirmed())

        for (i in 0 until 4) {
            val t = 16_000L + i * 4_000L
            edges += tracker.rot(t)
            edges += tracker.separator(t + 1_000)
        }
        edges += tracker.sweep(dd, 60_000)

        assertEquals(4, tracker.confirmed())
        assertTrue(edges.none { it is RotEdge.Tripped })
    }

    // 5
    @Test
    fun `mixed rules trip and clear on their own rule only`() {
        val tracker = ParseRotTracker()
        assertTrue(tracker.fiveVisits().isEmpty())

        val fifth = tracker.healthy(19_000, ruleB)
        assertEquals(listOf(ruleA), fifth.filterIsInstance<RotEdge.Tripped>().map { it.cause.ruleId })

        val more = (0 until 5).flatMap { tracker.healthy(20_000L + it * 1_000L, ruleB) }
        assertTrue(more.isEmpty())
        assertFalse(tracker.isRotted(dd, ruleB))
        assertTrue("a healthy B never clears A", tracker.isRotted(dd, ruleA))

        assertTrue(tracker.healthy(26_000, ruleA).isEmpty())
        assertTrue(tracker.healthy(27_000, ruleA).isEmpty())
        assertEquals(listOf(RotEdge.Cleared(dd, ruleA)), tracker.healthy(28_000, ruleA))
        assertFalse(tracker.isRotted(dd, ruleA))
    }

    // 6
    @Test
    fun `pre-render visits do not reset the clear streak`() {
        val tracker = trippedA()

        val edges = (0 until 3).flatMap { i ->
            val t = 21_000L + i * 1_000L
            tracker.rot(t) + tracker.healthy(t + 200, ruleA)
        }

        assertEquals(listOf(RotEdge.Cleared(dd, ruleA)), edges)
        assertFalse(tracker.isRotted(dd, ruleA))
    }

    // 7
    @Test
    fun `a confirmed rot visit resets the clear streak`() {
        val tracker = trippedA()
        assertTrue(tracker.healthy(21_000, ruleA).isEmpty())
        assertTrue(tracker.healthy(22_000, ruleA).isEmpty())

        tracker.rot(23_000)
        assertTrue(tracker.separator(26_000).isEmpty()) // confirms the visit: the streak starts over

        assertTrue(tracker.healthy(27_000, ruleA).isEmpty())
        assertTrue(tracker.healthy(28_000, ruleA).isEmpty())
        assertTrue("two healthy parses after the confirmed visit do not clear", tracker.isRotted(dd, ruleA))

        assertEquals(listOf(RotEdge.Cleared(dd, ruleA)), tracker.healthy(29_000, ruleA))
    }

    // 8
    @Test
    fun `a skipped branch of a healthy winner is healthy, never rot`() {
        val tracker = ParseRotTracker()
        for (i in 0 until 4) {
            val t = i * 4_000L
            tracker.rot(t)
            tracker.separator(t + 1_000)
        }
        tracker.sweep(dd, 15_000)
        assertEquals(4, tracker.confirmed())

        // A wins the frame while a sibling branch of A was skipped with null pay.
        assertTrue(tracker.record(dd, 16_000, ruleA, listOf(offerRot())).isEmpty())
        assertEquals(0, tracker.confirmed())

        assertTrue(tracker.sweep(dd, 30_000).isEmpty())
        assertEquals("the skipped shortfall started no occurrence", 0, tracker.confirmed())
        assertFalse(tracker.isRotted(dd, ruleA))
    }

    // 9
    @Test
    fun `only shape-required rot counts`() {
        val never = listOf(
            ParseShortfall(ruleId = "doordash.screen.task", shape = "task", allNullFieldCount = 2),
            ParseShortfall(ruleId = "doordash.screen.offer_total", shape = "offer", allNullFieldCount = 3),
            ParseShortfall(
                ruleId = "doordash.screen.sensitive",
                shape = "sensitive",
                allNullFieldCount = 1,
                nullRequiredFields = listOf("payAmount"),
            ),
            ParseShortfall(ruleId = "doordash.screen.no_shape", nullRequiredFields = listOf("payAmount")),
            ParseShortfall(
                ruleId = "doordash.screen.bind_only",
                shape = "offer",
                unresolvedOptionalBindings = listOf("x"),
            ),
        )
        for (shortfall in never) {
            val tracker = ParseRotTracker()
            val edges = (0 until 10).flatMap { i ->
                val t = i * 4_000L
                tracker.record(dd, t, null, listOf(shortfall)) + tracker.separator(t + 1_000)
            } + tracker.sweep(dd, 100_000)

            assertTrue("${shortfall.ruleId} must never count: $edges", edges.isEmpty())
            assertEquals(shortfall.ruleId, 0, tracker.confirmedOccurrences(dd, shortfall.ruleId))
        }
    }

    // 10
    @Test
    fun `two shortfalls of one rule on one frame are one rot frame`() {
        val tracker = ParseRotTracker()
        // Two skipped branches of A, listed distance first: the cause still reads in shape order.
        val frame = listOf(offerRot(fields = listOf("distance")), offerRot(fields = listOf("payAmount")))
        for (i in 0 until 4) {
            val t = i * 4_000L
            tracker.record(dd, t, null, frame)
            tracker.separator(t + 1_000)
        }
        tracker.sweep(dd, 15_000)
        assertEquals(4, tracker.confirmed())

        tracker.record(dd, 16_000, null, frame)
        assertEquals(
            listOf(RotEdge.Tripped(dd, ParseRotCause(ruleA, "offer", offerFields, trippedAt = 20_000))),
            tracker.sweep(dd, 20_000),
        )
    }

    // 11
    @Test
    fun `platforms are independent`() {
        val uberRule = "uber.screen.offer_card"

        val confirmOnly = ParseRotTracker()
        confirmOnly.rot(0)
        confirmOnly.healthy(5_000, uberRule, wire = uber)
        assertEquals("an Uber frame never confirms DoorDash's pending", 0, confirmOnly.confirmed())
        confirmOnly.separator(5_000)
        assertEquals(1, confirmOnly.confirmed())

        val oneVisit = ParseRotTracker()
        oneVisit.rot(0)
        oneVisit.separator(1_000, wire = uber)
        oneVisit.separator(2_000, wire = uber)
        oneVisit.rot(3_500)
        oneVisit.separator(4_000, wire = uber)
        oneVisit.rot(7_000)
        oneVisit.sweep(dd, 20_000)
        assertEquals("Uber frames between DoorDash rot frames do not split the visit", 1, oneVisit.confirmed())
    }

    // 12
    @Test
    fun `occurrence starts are spaced by the window`() {
        // (a) Seven quick visits are one occurrence.
        val flicker = ParseRotTracker()
        val edges = (0 until 7).flatMap { i ->
            val t = i * 300L
            flicker.rot(t) + flicker.separator(t + 150)
        } + flicker.sweep(dd, 10_000)
        assertEquals(1, flicker.confirmed())
        assertTrue(edges.isEmpty())

        // (b) The boundary: a start 2 999 ms after the last is muted, 3 000 ms is not.
        val under = ParseRotTracker()
        under.rot(0); under.separator(100); under.rot(2_999); under.separator(3_100)
        under.sweep(dd, 10_000)
        assertEquals(1, under.confirmed())

        val at = ParseRotTracker()
        at.rot(0); at.separator(100); at.rot(3_000); at.separator(3_100)
        at.sweep(dd, 10_000)
        assertEquals(2, at.confirmed())

        // (c) A discarded pre-render start does not mute the next real visit.
        val reset = ParseRotTracker()
        reset.rot(0); reset.healthy(200, ruleA); reset.rot(1_000); reset.separator(1_100)
        reset.sweep(dd, 10_000)
        assertEquals(1, reset.confirmed())
    }

    // 13
    @Test
    fun `trips come before clears on one frame`() {
        val tracker = ParseRotTracker()
        for (i in 0 until 5) {
            val t = i * 4_000L
            tracker.rot(t)
            tracker.separator(t + 1_000)
        }
        tracker.sweep(dd, 20_000)
        assertTrue(tracker.isRotted(dd, ruleA))
        tracker.healthy(20_500, ruleA)
        tracker.healthy(21_000, ruleA)

        // B: four confirmed receipt visits and a fifth pending since 37 500.
        val receiptRot = listOf(ParseShortfall(ruleId = ruleB, shape = "post_task", nullRequiredFields = listOf("totalPay")))
        for (i in 0 until 5) {
            val t = 21_500L + i * 4_000L
            tracker.record(dd, t, ruleB, receiptRot)
            tracker.separator(t + 1_000)
        }
        assertEquals(4, tracker.confirmed(ruleB))

        assertEquals(
            listOf(
                RotEdge.Tripped(dd, ParseRotCause(ruleB, "post_task", listOf("totalPay"), trippedAt = 40_500)),
                RotEdge.Cleared(dd, ruleA),
            ),
            tracker.healthy(40_500, ruleA),
        )
    }
}
