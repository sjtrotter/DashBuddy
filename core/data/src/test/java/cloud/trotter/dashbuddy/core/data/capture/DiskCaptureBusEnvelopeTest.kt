package cloud.trotter.dashbuddy.core.data.capture

import android.content.Context
import cloud.trotter.dashbuddy.domain.capture.CensusEnvelopeSink
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.pipeline.PipelineRegistry
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class DiskCaptureBusEnvelopeTest {
    private class Sink : CensusEnvelopeSink {
        override var isEnabled = true
        var fail = false
        val held = mutableListOf<Triple<String, Platform, String>>()
        override fun hold(captureId: String, platform: Platform, envelopeJson: String) {
            if (fail) throw AssertionError("test_failure")
            held += Triple(captureId, platform, envelopeJson)
        }
        override fun pair(captureId: String, fingerprint: String) = false
    }

    @Test fun `UNKNOWN screen tap runs synchronously before IO and respects source classification and dedup`() = runTest {
        val sink = Sink()
        // An independent scheduler deliberately never runs the disk writer; hold must not wait for it.
        val bus = DiskCaptureBus(mock<Context>(), StandardTestDispatcher(), sink, CensusUploadStats())
        val source = PipelineRegistry.SCREEN_PIPELINE_ID
        assertEquals("screen", bus.offer("screen", source, null, "doordash", "screen-json", 1))
        assertEquals(listOf(Triple("screen", Platform.DoorDash, "screen-json")), sink.held)
        assertNull(bus.offer("duplicate", source, null, "doordash", "screen-json", 1))
        bus.offer("known", source, "offer", "doordash", "{}", null)
        bus.offer("click", "accessibility.click", null, "doordash", "{}", null)
        bus.offer("notification", "notification", null, "doordash", "{}", null)
        assertEquals("unknown-platform", bus.offer("unknown-platform", source, null, "invalid", "{}", null))
        sink.isEnabled = false
        bus.offer("disabled", source, null, "doordash", "{}", null)
        assertEquals(1, sink.held.size)
        sink.isEnabled = true
        bus.offer("writer-unknown", source, UNKNOWN_TARGET, "doordash", "writer-json", null)
        assertEquals(Triple("writer-unknown", Platform.DoorDash, "writer-json"), sink.held.last())
        assertEquals(2, sink.held.size)
    }

    @Test fun `tap throwable is counted and never costs the capture`() = runTest {
        val sink = Sink().apply { fail = true }
        val stats = CensusUploadStats()
        val bus = DiskCaptureBus(mock<Context>(), StandardTestDispatcher(), sink, stats)
        assertEquals("screen", bus.offer("screen", PipelineRegistry.SCREEN_PIPELINE_ID, null, "doordash", "{}", null))
        assertEquals(1L, stats.envelopesDropped.get())
        assertTrue(sink.held.isEmpty())
    }
}
