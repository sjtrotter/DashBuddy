package cloud.trotter.dashbuddy.core.pipeline.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Tests for the deadline-rollover behavior in `TransformRegistry.parseDeadline`
 * / `parseTime`.
 *
 * Background: prior to the 2026-05-19 fix the transform unconditionally rolled
 * a parsed time forward a day if it was in the past relative to "now". That
 * caused the "1434:38" ghost-countdown bug (field log 2026-05-19 #2) when
 * the dasher arrived ~37 seconds past the pickup-by deadline and the next
 * re-parse rolled the deadline ~24 hours forward.
 *
 * The threshold logic is extracted to [TransformRegistry.applyRollover] so it
 * can be unit-tested with controlled inputs (no dependency on the wall clock).
 * The end-to-end text-parsing path is then exercised with structural assertions
 * that don't depend on the current time of day.
 */
class TransformRegistryTest {

    private val hour = 3_600_000L
    private val day = 24L * hour
    private val threshold = TransformRegistry.ROLLOVER_THRESHOLD_MS  // 12h

    // =========================================================================
    // Pure rollover logic — testable without the wall clock
    // =========================================================================

    @Test
    fun `applyRollover keeps future target as-is`() {
        val now = 1_000_000_000L
        val target = now + 30 * 60 * 1000L  // 30 min ahead
        assertEquals(target, TransformRegistry.applyRollover(target, now))
    }

    @Test
    fun `applyRollover keeps slightly-past target as-is (the 1434 ghost fix)`() {
        val now = 1_000_000_000L
        val target = now - 60 * 1000L  // 1 min ago
        assertEquals(target, TransformRegistry.applyRollover(target, now))
    }

    @Test
    fun `applyRollover keeps target past by under threshold as-is`() {
        val now = 1_000_000_000L
        val target = now - 11 * hour  // 11h ago (just under 12h threshold)
        assertEquals(target, TransformRegistry.applyRollover(target, now))
    }

    @Test
    fun `applyRollover at exactly the threshold keeps target as-is`() {
        val now = 1_000_000_000L
        val target = now - threshold  // exactly 12h ago
        // pastMillis == threshold, condition is `>` not `>=`, so stays
        assertEquals(target, TransformRegistry.applyRollover(target, now))
    }

    @Test
    fun `applyRollover at one ms over threshold rolls forward`() {
        val now = 1_000_000_000L
        val target = now - (threshold + 1)
        assertEquals(target + day, TransformRegistry.applyRollover(target, now))
    }

    @Test
    fun `applyRollover far past rolls forward (late-night offer for next morning)`() {
        val now = 1_000_000_000L
        val target = now - 17 * hour  // 17h ago (analogous to 11pm-for-6am)
        assertEquals(target + day, TransformRegistry.applyRollover(target, now))
    }

    @Test
    fun `applyRollover bounds result within plus-minus 24h of now`() {
        val now = 1_000_000_000L
        // Across a sweep of past offsets, result should always be within ±24h.
        for (hoursPast in 0..23) {
            val target = now - hoursPast * hour
            val result = TransformRegistry.applyRollover(target, now)
            val delta = result - now
            assertTrue(
                "Result should be within ±24h of now (hoursPast=$hoursPast, delta=${delta}ms)",
                delta in (-day)..(day),
            )
        }
    }

    // =========================================================================
    // End-to-end text parsing — structural assertions only
    // =========================================================================

    @Test
    fun `parseDeadline regression — slightly past wall-clock stays near now`() {
        // Field log 2026-05-19 #2 regression: dasher arrives ~1 min past the
        // pickup-by deadline; previously the result was rolled ~24h forward.
        // With the threshold fix, the result must remain near now (in the past
        // or barely future, NOT ~24h ahead).
        val now = Calendar.getInstance()
        val targetCal = (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, -1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val timeText = String.format(
            java.util.Locale.US,
            "%d:%02d %s",
            ((targetCal.get(Calendar.HOUR_OF_DAY) + 11) % 12) + 1,
            targetCal.get(Calendar.MINUTE),
            if (targetCal.get(Calendar.HOUR_OF_DAY) < 12) "AM" else "PM",
        )
        val result = TransformRegistry.apply("parseDeadline", "Pick up by $timeText") as? Long
        assertNotNull(result)
        val delta = result!! - now.timeInMillis
        assertTrue(
            "Result should be near now (not rolled ~24h forward). delta=${delta}ms",
            delta in (-(2 * 60_000L))..(2 * 60_000L),
        )
    }

    @Test
    fun `parseTime handles 24-hour HH mm format`() {
        val now = Calendar.getInstance()
        val target = (now.clone() as Calendar).apply {
            add(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val hh = target.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
        val mm = target.get(Calendar.MINUTE).toString().padStart(2, '0')
        val result = TransformRegistry.apply("parseTime", "$hh:$mm") as? Long
        assertNotNull(result)
        assertEquals(target.timeInMillis, result)
    }

    @Test
    fun `unparseable text returns null`() {
        assertNull(TransformRegistry.apply("parseDeadline", "no time here"))
        assertNull(TransformRegistry.apply("parseTime", "garbage"))
    }

    // #823 Phase 1 — item-count denomination. parseItemCountUnit reads the FIRST count token's label
    // off the SAME node as parseItemCount, so the pair never disagrees about which number was read.

    @Test
    fun `parseItemCountUnit reads UNITS for a units-only render`() {
        assertEquals("UNITS", TransformRegistry.apply("parseItemCountUnit", "(64 units)"))
        assertEquals("UNITS", TransformRegistry.apply("parseItemCountUnit", "(1 unit)"))
    }

    @Test
    fun `parseItemCountUnit reads ITEMS when the leading count is items`() {
        assertEquals("ITEMS", TransformRegistry.apply("parseItemCountUnit", "(4 items)"))
        // Items•units: parseItemCount grabs the leading "9", so its denomination is ITEMS.
        assertEquals("ITEMS", TransformRegistry.apply("parseItemCountUnit", "(9 items • 11 units)"))
    }

    @Test
    fun `parseItemCount and parseItemCountUnit agree on the token they read`() {
        assertEquals(9, TransformRegistry.apply("parseItemCount", "(9 items • 11 units)"))
        assertEquals("ITEMS", TransformRegistry.apply("parseItemCountUnit", "(9 items • 11 units)"))
        assertEquals(64, TransformRegistry.apply("parseItemCount", "(64 units)"))
        assertEquals("UNITS", TransformRegistry.apply("parseItemCountUnit", "(64 units)"))
    }

    @Test
    fun `parseItemCountUnit returns null when no count token is present`() {
        assertNull(TransformRegistry.apply("parseItemCountUnit", "H-E-B"))
        assertNull(TransformRegistry.apply("parseItemCountUnit", "no count here"))
    }
}
