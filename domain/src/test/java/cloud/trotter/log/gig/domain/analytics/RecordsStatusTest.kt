package cloud.trotter.log.gig.domain.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordsStatusTest {

    @Test
    fun `the rebuilding flag wins over any watermark`() {
        assertEquals(RecordsStatus.REBUILDING, RecordsStatus.of(rebuilding = true, watermark = 10, latestEventId = 10))
        assertEquals(RecordsStatus.REBUILDING, RecordsStatus.of(rebuilding = true, watermark = 0, latestEventId = 10))
        assertEquals(RecordsStatus.REBUILDING, RecordsStatus.of(rebuilding = true, watermark = null, latestEventId = null))
    }

    @Test
    fun `a watermark behind the latest event id is updating`() {
        assertEquals(RecordsStatus.UPDATING, RecordsStatus.of(rebuilding = false, watermark = 9, latestEventId = 10))
        assertEquals(RecordsStatus.UPDATING, RecordsStatus.of(rebuilding = false, watermark = null, latestEventId = 1))
    }

    @Test
    fun `caught up or an empty log is up to date`() {
        assertEquals(RecordsStatus.UP_TO_DATE, RecordsStatus.of(rebuilding = false, watermark = 10, latestEventId = 10))
        assertEquals(RecordsStatus.UP_TO_DATE, RecordsStatus.of(rebuilding = false, watermark = null, latestEventId = null))
        assertEquals(RecordsStatus.UP_TO_DATE, RecordsStatus.of(rebuilding = false, watermark = 4, latestEventId = null))
    }
}
