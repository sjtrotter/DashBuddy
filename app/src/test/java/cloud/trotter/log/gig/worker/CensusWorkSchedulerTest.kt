package cloud.trotter.log.gig.worker

import androidx.work.NetworkType
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1620 (ADR-0011 §21): the census's one scheduled run is the daily window, and Wi-Fi only means unmetered. */
class CensusWorkSchedulerTest {

    @Test fun `Wi-Fi only waits for an unmetered network, otherwise any connection`() {
        assertEquals(NetworkType.UNMETERED, CensusWorkScheduler.constraints(wifiOnly = true).requiredNetworkType)
        assertEquals(NetworkType.CONNECTED, CensusWorkScheduler.constraints(wifiOnly = false).requiredNetworkType)
        assertTrue(CensusWorkScheduler.constraints(wifiOnly = true).requiresBatteryNotLow())
    }

    @Test fun `the daily request runs at the drawn time, tagged so only it draws the next one`() {
        val runAt = 1_763_100_000_000L
        val request = CensusWorkScheduler.dailyRequest(runAt, wifiOnly = true)
        assertEquals(runAt, request.workSpec.nextScheduleTimeOverride)
        assertEquals(24 * 3_600_000L, request.workSpec.intervalDuration)
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
        assertTrue(CensusWorkScheduler.DAILY_TAG in request.tags)
    }

    @Test fun `nothing a frame does can ask for an upload`() {
        // The scheduler's whole surface: the test build's manual run, the daily window and cancelling a stale manual
        // run. The five-minute and
        // every-fifty-items requests a queued skeleton used to make are gone (#1620).
        assertEquals(
            setOf("enqueueNow", "scheduleDaily", "cancelManual"),
            CensusUploadScheduler::class.java.declaredMethods.map { it.name }.filterNot { it.contains('$') }.toSet(),
        )
    }
}
