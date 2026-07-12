package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.state.ParsedFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #430 — gate/restart counters and the summary line. */
class PipelineStatsTest {

    @Test
    fun `content gate drops are split by sensitive vs noise`() {
        val stats = PipelineStats()
        stats.onContentGateDrop(ParsedFields.SensitiveFields())
        stats.onContentGateDrop(ParsedFields.SensitiveFields())
        stats.onContentGateDrop(ParsedFields.NoiseFields())

        assertEquals(2L, stats.droppedSensitiveCount)
        assertEquals(1L, stats.droppedNoiseCount)
    }

    @Test
    fun `counters increment independently and appear in the summary`() {
        val stats = PipelineStats()
        stats.onDisabledPlatformDrop()
        stats.onDuplicateSuppressed()
        stats.onDuplicateSuppressed()
        stats.onUnknownDropped()
        stats.onMappingFailure()
        assertEquals(1L, stats.onPipelineRestart())
        assertEquals(2L, stats.onPipelineRestart())
        stats.onForwarded()

        assertEquals(1L, stats.droppedDisabledPlatformCount)
        assertEquals(2L, stats.suppressedDuplicateCount)
        assertEquals(1L, stats.droppedUnknownCount)
        assertEquals(1L, stats.mappingFailureCount)
        assertEquals(2L, stats.restartCount)
        assertEquals(1L, stats.forwardedCount)

        val summary = stats.summary()
        assertTrue(summary.contains("forwarded=1"))
        assertTrue(summary.contains("dupSuppressed=2"))
        assertTrue(summary.contains("restarts=2"))
        assertTrue(summary.contains("mappingFailures=1"))
    }

    /** #731 — quantify the field-observed notification-listener rebind (129-240x/day). */
    @Test
    fun `notif listener connect and disconnect counters track independently and appear in the summary`() {
        val stats = PipelineStats()

        assertEquals(1L, stats.onNotifListenerConnected())
        assertEquals(2L, stats.onNotifListenerConnected())
        assertEquals(1L, stats.onNotifListenerDisconnected())

        assertEquals(2L, stats.notifListenerConnectCount)
        assertEquals(1L, stats.notifListenerDisconnectCount)

        val summary = stats.summary()
        assertTrue(summary.contains("notifListenerConnects=2"))
        assertTrue(summary.contains("notifListenerDisconnects=1"))
    }
}
