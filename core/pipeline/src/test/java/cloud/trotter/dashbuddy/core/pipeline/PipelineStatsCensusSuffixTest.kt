package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1146: census summaries are counts only and absent until a census counter moves. */
class PipelineStatsCensusSuffixTest {

    @Test
    fun `upload counters share the census suffix without pipeline dependency on data`() {
        val uploads = CensusUploadStats()
        val stats = PipelineStats("test", uploads)
        uploads.spooled.incrementAndGet()
        uploads.spoolDropped.incrementAndGet()
        uploads.uploaded.addAndGet(2)
        uploads.duplicate.addAndGet(3)
        uploads.uploadFailures.incrementAndGet()
        uploads.reject(mapOf("bad_hash" to 4))
        assertEquals(1L, stats.censusSpooled)
        assertEquals(1L, stats.censusSpoolDropped)
        assertEquals(2L, stats.censusUploaded)
        assertEquals(3L, stats.censusDuplicate)
        assertEquals(1L, stats.censusUploadFailures)
        assertEquals(mapOf("bad_hash" to 4L), stats.censusRejected)
        assertTrue(stats.summary().contains("spooled=1,spoolDropped=1,corrupt=0,spoolOversized=0,uploaded=2,duplicate=3,uploadFailures=1,rejected={bad_hash=4}"))
    }

    @Test
    fun `server oversized and bad request counters independently render in census summary`() {
        val oversized = CensusUploadStats()
        oversized.oversized.incrementAndGet()
        assertTrue(PipelineStats("test", oversized).summary().endsWith("oversized=1,badRequest=0}"))
        val badRequest = CensusUploadStats()
        badRequest.badRequest.incrementAndGet()
        assertTrue(PipelineStats("test", badRequest).summary().endsWith("oversized=0,badRequest=1}"))
    }

    @Test
    fun `untouched stats have no census suffix`() {
        assertFalse(PipelineStats().summary().contains("census{"))
    }

    @Test
    fun `skeleton and refusal counts render in the census suffix`() {
        val stats = PipelineStats()
        stats.onCensusSkeleton(3, 2)
        stats.onCensusRefused(Refusal.OVERSIZE)

        assertTrue(stats.summary().endsWith(
            " census{skeletons=1,hashed=3,withheld=2,sinkRefused=0,failures=0,unattributed=0,refused{OVERSIZE=1}}",
        ))
    }

    @Test
    fun `built skeletons accumulate tokens and omit empty refusals`() {
        val stats = PipelineStats()
        stats.onCensusSkeleton(3, 2)
        stats.onCensusSkeleton(1, 4)
        stats.onCensusSinkRefused()
        stats.onCensusPublishFailure()

        assertEquals(2L, stats.censusSkeletonCount())
        assertEquals(4L, stats.censusTokensHashedCount())
        assertEquals(6L, stats.censusTokensWithheldCount())
        assertEquals(1L, stats.censusSinkRefusedCount())
        assertEquals(1L, stats.censusPublishFailureCount())
        assertTrue(stats.summary().endsWith(
            " census{skeletons=2,hashed=4,withheld=6,sinkRefused=1,failures=1,unattributed=0}",
        ))
    }

    @Test
    fun `refusals render without skeletons and in declaration order`() {
        val stats = PipelineStats()
        stats.onCensusRefused(Refusal.OVERSIZE)
        stats.onCensusRefused(Refusal.SENSITIVE_FRAME)
        stats.onCensusRefused(Refusal.OVERSIZE)

        assertTrue(stats.summary().endsWith(
            " census{skeletons=0,hashed=0,withheld=0,sinkRefused=0,failures=0,unattributed=0," +
                "refused{SENSITIVE_FRAME=1,OVERSIZE=2}}",
        ))
    }

    @Test
    fun `a failure alone renders the census suffix`() {
        val stats = PipelineStats()
        stats.onCensusPublishFailure()

        assertTrue(stats.summary().endsWith(
            " census{skeletons=0,hashed=0,withheld=0,sinkRefused=0,failures=1,unattributed=0}",
        ))
    }
}
