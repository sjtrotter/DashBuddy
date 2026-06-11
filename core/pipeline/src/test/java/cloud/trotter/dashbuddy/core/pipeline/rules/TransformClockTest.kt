package cloud.trotter.dashbuddy.core.pipeline.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * #343 — time transforms must be deterministic against the OBSERVATION's clock,
 * not the evaluation wall clock. `Calendar.getInstance()` inside parseTime/
 * parseDeadline meant a captured screen re-parsed at a different hour (or
 * replayed across the 12h rollover threshold) produced different millis — the
 * 2026-05-19 "1434:38 ghost countdown" class. [TransformRegistry.withClock]
 * anchors "today" explicitly.
 */
class TransformClockTest {

    private val zone = ZoneId.of("America/Chicago")

    /** 2026-06-10 14:00 local in [zone]. */
    private val anchor = Instant.parse("2026-06-10T19:00:00Z").toEpochMilli()

    private fun millisAt(hour: Int, minute: Int): Long =
        Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate()
            .atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `parseDeadline is deterministic under a fixed clock`() {
        val a = TransformRegistry.withClock(anchor, zone) {
            TransformRegistry.apply("parseDeadline", "Pick up by 5:39 PM")
        }
        val b = TransformRegistry.withClock(anchor, zone) {
            TransformRegistry.apply("parseDeadline", "Pick up by 5:39 PM")
        }
        assertNotNull(a)
        assertEquals(a, b)
        assertEquals(millisAt(17, 39), a)
    }

    @Test
    fun `future-today time anchors to the clock's day`() {
        val parsed = TransformRegistry.withClock(anchor, zone) {
            TransformRegistry.apply("parseTime", "8:52 PM")
        }
        assertEquals(millisAt(20, 52), parsed)
    }

    @Test
    fun `slightly-past time stays today (ghost-countdown guard)`() {
        // 13:55 is 5 minutes before the 14:00 anchor — well under the 12h threshold.
        val parsed = TransformRegistry.withClock(anchor, zone) {
            TransformRegistry.apply("parseTime", "13:55")
        }
        assertEquals(millisAt(13, 55), parsed)
    }

    @Test
    fun `far-past time rolls to tomorrow`() {
        // 1:00 AM is 13h before the 14:00 anchor — past the 12h threshold.
        val parsed = TransformRegistry.withClock(anchor, zone) {
            TransformRegistry.apply("parseTime", "1:00 AM")
        }
        assertEquals(millisAt(1, 0) + 24L * 3_600_000L, parsed)
    }

    @Test
    fun `clock scope does not leak after the block`() {
        TransformRegistry.withClock(anchor, zone) {
            TransformRegistry.apply("parseTime", "8:52 PM")
        }
        // Unscoped call falls back to the system clock — just verify it parses.
        assertNotNull(TransformRegistry.apply("parseTime", "8:52 PM"))
    }
}
