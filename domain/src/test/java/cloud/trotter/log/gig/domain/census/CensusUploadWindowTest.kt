package cloud.trotter.log.gig.domain.census

import cloud.trotter.log.gig.domain.census.CensusUploadWindow.DAY_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

/** #1620 (ADR-0011 §21): the daily window is a random time in the next UTC day, whatever time it is drawn at. */
class CensusUploadWindowTest {
    private val dayStart = Instant.parse("2026-11-14T00:00:00Z").toEpochMilli()

    @Test fun `the time falls in the next UTC day from any time of day`() {
        for (drawnAt in listOf("2026-11-13T00:00:00Z", "2026-11-13T07:45:00Z", "2026-11-13T23:59:59.999Z")) {
            val at = CensusUploadWindow.next(Instant.parse(drawnAt).toEpochMilli(), Random(3))
            assertTrue(drawnAt, at in dayStart until dayStart + DAY_MILLIS)
        }
    }

    @Test fun `the time of day follows the random source, not the time it was drawn`() {
        val noon = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
        val morning = Instant.parse("2026-11-13T06:00:00Z").toEpochMilli()
        assertEquals(CensusUploadWindow.next(noon, Random(5)), CensusUploadWindow.next(morning, Random(5)))
        val times = (1..50).map { CensusUploadWindow.next(noon, Random(it)) - dayStart }
        assertTrue(times.all { it in 0 until DAY_MILLIS })
        assertTrue("spread over the day", times.max() - times.min() > DAY_MILLIS / 2)
    }
}
