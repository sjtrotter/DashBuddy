package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder.Refusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1146: census summaries are counts only and absent until a census counter moves. */
class PipelineStatsCensusSuffixTest {

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
            " census{skeletons=1,hashed=3,withheld=2,sinkRefused=0,failures=0,refused{OVERSIZE=1}}",
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
            " census{skeletons=2,hashed=4,withheld=6,sinkRefused=1,failures=1}",
        ))
    }

    @Test
    fun `refusals render without skeletons and in declaration order`() {
        val stats = PipelineStats()
        stats.onCensusRefused(Refusal.OVERSIZE)
        stats.onCensusRefused(Refusal.SENSITIVE_FRAME)
        stats.onCensusRefused(Refusal.OVERSIZE)

        assertTrue(stats.summary().endsWith(
            " census{skeletons=0,hashed=0,withheld=0,sinkRefused=0,failures=0," +
                "refused{SENSITIVE_FRAME=1,OVERSIZE=2}}",
        ))
    }

    @Test
    fun `a failure alone renders the census suffix`() {
        val stats = PipelineStats()
        stats.onCensusPublishFailure()

        assertTrue(stats.summary().endsWith(
            " census{skeletons=0,hashed=0,withheld=0,sinkRefused=0,failures=1}",
        ))
    }
}
