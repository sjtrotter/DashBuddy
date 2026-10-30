package cloud.trotter.log.gig.feature.bubble.hud.odometer

import cloud.trotter.log.gig.domain.model.vehicle.LastOdometerReading
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerCueTest {
    private val start = 1_700_000_000_000L
    private val end = start + 3_600_000L
    private fun reading(at: Long) = LastOdometerReading(52_388, 100.0, at)
    private fun shows(
        startedAt: Long? = start, endedAt: Long? = null, last: LastOdometerReading? = null,
        enabled: Boolean = true, loaded: Boolean = true, now: Long = start,
    ) = OdometerCue.shows(enabled, startedAt, endedAt, last, loaded, now)

    @Test fun `start cue lasts less than ten minutes`() {
        assertFalse(shows(now = start - 1))
        assertTrue(shows())
        assertTrue(shows(now = start + OdometerCue.WINDOW_MS - 1))
        assertFalse(shows(now = start + OdometerCue.WINDOW_MS))
        assertFalse(shows(last = reading(start)))
    }

    @Test fun `a reading before the start window keeps the cue`() {
        assertTrue(shows(last = reading(start - OdometerCue.WINDOW_MS - 1)))
    }

    @Test fun `a reading at the start boundary suppresses the cue`() {
        assertFalse(shows(last = reading(start - OdometerCue.WINDOW_MS)))
        assertFalse(shows(last = reading(start - 60_000L)))
    }

    @Test fun `end cue persists until a reading in or after its window`() {
        val later = end + 86_400_000L
        assertTrue(shows(startedAt = null, endedAt = end, now = later))
        assertTrue(shows(startedAt = null, endedAt = end,
            last = reading(end - OdometerCue.WINDOW_MS - 1), now = later))
        assertFalse(shows(startedAt = null, endedAt = end, last = reading(end + 1), now = later))
    }

    @Test fun `a reading in the dash last ten minutes suppresses the resting cue`() {
        for (at in listOf(end - 60_000L, end - OdometerCue.WINDOW_MS)) {
            assertFalse(shows(startedAt = null, endedAt = end, last = reading(at), now = end))
        }
    }

    @Test fun `pref off suppresses both cues`() {
        assertFalse(shows(enabled = false))
        assertFalse(shows(startedAt = null, endedAt = end, enabled = false))
    }

    @Test fun `no session shows no cue`() {
        assertFalse(shows(startedAt = null))
    }

    @Test fun `not loaded suppresses both cues`() {
        assertFalse(shows(loaded = false))
        assertFalse(shows(startedAt = null, endedAt = end, loaded = false))
    }

    @Test fun `a live session takes precedence over a previous end`() {
        assertFalse(shows(endedAt = start - 1, now = start + OdometerCue.WINDOW_MS))
    }
}
