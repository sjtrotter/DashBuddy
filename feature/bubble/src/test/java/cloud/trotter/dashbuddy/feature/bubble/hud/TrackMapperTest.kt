package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

class TrackMapperTest {
    private fun track(kind: LegKind = LegKind.PICKUP_NAV, now: Long = 160_000,
        anchor: Long = 100_000, due: Long? = 220_000) = TrackMapper.map(TrackLeg(kind, anchor, due), now)

    @Test fun `pickup navigation runs from the nav pin to the store at the pickup deadline`() {
        val t = track()
        assertEquals(TrackIcon.NAV_PIN, t.anchorIcon)
        assertEquals(100_000L, t.anchorAt)
        assertEquals(TrackIcon.STORE, t.endIcon)
        assertEquals(220_000L, t.endAt)
        assertNull(t.markAt)
        assertNull(t.markFraction)
    }
    @Test fun `pickup arrived runs from the store to the done pin at the deadline`() {
        val t = track(LegKind.PICKUP_ARRIVED)
        assertEquals(TrackIcon.STORE, t.anchorIcon)
        assertEquals(TrackIcon.DONE_PIN, t.endIcon)
        assertEquals(220_000L, t.endAt)
    }
    @Test fun `pickup late keeps the store anchor with the stopwatch at the deadline and the end at now`() {
        val t = track(LegKind.PICKUP_ARRIVED, 340_000)
        assertEquals(TrackIcon.STORE, t.anchorIcon)
        assertEquals(100_000L, t.anchorAt)
        assertNull(t.endIcon)
        assertEquals(340_000L, t.endAt)
        assertEquals(220_000L, t.markAt)
        assertTrue(t.late)
        assertEquals(0.5f, t.markFraction!!, 0.0001f)
        assertEquals(1f, t.fill, 0f)
    }
    @Test fun `dropoff navigation runs from the nav pin to the house at the delivery deadline`() {
        val t = track(LegKind.DROPOFF_NAV)
        assertEquals(TrackIcon.NAV_PIN, t.anchorIcon)
        assertEquals(TrackIcon.HOUSE, t.endIcon)
        assertEquals(220_000L, t.endAt)
    }
    @Test fun `dropoff arrived runs from the house to the done pin`() {
        val t = track(LegKind.DROPOFF_ARRIVED)
        assertEquals(TrackIcon.HOUSE, t.anchorIcon)
        assertEquals(TrackIcon.DONE_PIN, t.endIcon)
    }
    @Test fun `dropoff late keeps the house anchor with the stopwatch mark`() {
        val t = track(LegKind.DROPOFF_ARRIVED, 340_000)
        assertEquals(TrackIcon.HOUSE, t.anchorIcon)
        assertEquals(100_000L, t.anchorAt)
        assertEquals(220_000L, t.markAt)
        assertEquals(340_000L, t.endAt)
        assertNull(t.endIcon)
        assertEquals(0.5f, t.markFraction!!, 0f)
    }
    @Test fun `fill creeps toward the deadline`() {
        assertEquals(0.5f, track().fill, 0.0001f)
        assertEquals(0.75f, track(now = 190_000).fill, 0.0001f)
    }
    @Test fun `one millisecond before the deadline is not late`() {
        assertFalse(track(now = 219_999).late)
        assertEquals(TrackIcon.STORE, track(now = 219_999).endIcon)
    }
    @Test fun `exactly at the deadline is late`() {
        val t = track(now = 220_000)
        assertTrue(t.late)
        assertEquals(1f, t.markFraction!!, 0f)
        assertNull(t.endIcon)
    }
    @Test fun `stopwatch drifts toward the anchor as lateness grows`() {
        assertTrue(track(now = 460_000).markFraction!! < track(now = 340_000).markFraction!!)
    }
    @Test fun `navigation past the deadline drops the end icon and marks the deadline`() {
        listOf(LegKind.PICKUP_NAV, LegKind.DROPOFF_NAV).forEach {
            val t = track(it, 340_000)
            assertEquals(TrackIcon.NAV_PIN, t.anchorIcon)
            assertEquals(100_000L, t.anchorAt)
            assertNull(t.endIcon)
            assertEquals(220_000L, t.markAt)
            assertTrue(t.late)
        }
    }
    @Test fun `no deadline gives an open track with no fill`() {
        val t = track(due = null)
        assertNull(t.endAt)
        assertNull(t.markAt)
        assertNull(t.markFraction)
        assertEquals(TrackIcon.STORE, t.endIcon)
        assertEquals(0f, t.fill, 0f)
        assertFalse(t.late)
    }
    @Test fun `now before the anchor clamps the fill to zero`() {
        assertEquals(0f, track(now = 99_000).fill, 0f)
    }
    @Test fun `deadline before the anchor puts the mark at the anchor`() {
        assertEquals(0f, track(due = 90_000).markFraction!!, 0f)
    }
    @Test fun `zero span does not divide by zero`() {
        val t = track(now = 100_000, due = 100_000)
        assertEquals(0f, t.markFraction!!, 0f)
        assertEquals(1f, t.fill, 0f)
    }
}
