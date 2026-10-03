package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.census.HealthSink
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import timber.log.Timber
import cloud.trotter.dashbuddy.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #937 — the side-effecting edge of the recognition-health alarm.
 *
 * The pure decision has its own test ([RecognitionHealthTest]); this one covers what the
 * monitor adds: package→platform resolution through the registry, one report per platform per
 * process, and — the property that actually matters on a dash — fail-open. This runs once per
 * admitted frame, so a broken reporter must cost a log line, never a frame.
 */
class RecognitionHealthMonitorTest {

    private class Recorder : RecognitionHealthReporter {
        val calls = mutableListOf<Pair<String, Int>>()
        override fun onRecognitionDegraded(platformWire: String, unknownPercent: Int) {
            calls += platformWire to unknownPercent
        }
    }

    private data class ScreenCall(val timestamp: Long, val platform: String, val version: String?, val rule: String?)
    private class Sink : HealthSink {
        val screens = mutableListOf<ScreenCall>()
        val trips = mutableListOf<ScreenCall>()
        override fun onScreen(timestampMillis: Long, platformWire: String, platformAppVersion: String?, ruleId: String?) {
            screens += ScreenCall(timestampMillis, platformWire, platformAppVersion, ruleId)
        }
        override fun onTrip(timestampMillis: Long, platformWire: String, platformAppVersion: String?) {
            trips += ScreenCall(timestampMillis, platformWire, platformAppVersion, null)
        }
    }

    private fun screen(unknown: Boolean, version: String? = "7.1") = Observation.Screen(
        timestamp = 123L, captureId = null, ruleId = "doordash.screen.offer",
        metadata = ReplayMetadata.EMPTY.copy(platformAppVersion = version),
        flow = null, modeHint = null, parsed = ParsedFields.None,
        target = if (unknown) UNKNOWN_TARGET else "offer",
    )

    private val doordash = requireNotNull(Platform.DoorDash.packageName)
    private val uber = requireNotNull(Platform.Uber.packageName)

    private fun RecognitionHealthMonitor.feed(pkg: String?, unknown: Boolean, times: Int) {
        repeat(times) { onScreenAdmitted(pkg, screen(unknown)) }
    }

    @Test
    fun `a collapsed platform is reported once, by wire, with its percentage`() {
        val reporter = Recorder()
        val monitor = RecognitionHealthMonitor(reporter, Sink())

        monitor.feed(doordash, unknown = true, times = RecognitionHealth.WINDOW_SIZE * 3)

        assertEquals(listOf("doordash" to 100), reporter.calls)
    }

    @Test
    fun `a healthy platform is never reported`() {
        val reporter = Recorder()
        val monitor = RecognitionHealthMonitor(reporter, Sink())

        repeat(RecognitionHealth.WINDOW_SIZE * 4) { i ->
            monitor.onScreenAdmitted(doordash, screen(unknown = i % 6 == 0))
        }

        assertTrue(reporter.calls.isEmpty())
    }

    @Test
    fun `an unattributable frame is ignored - no platform, no recognition contract`() {
        val reporter = Recorder()
        val monitor = RecognitionHealthMonitor(reporter, Sink())

        monitor.feed("com.android.settings", unknown = true, times = RecognitionHealth.WINDOW_SIZE * 2)
        monitor.feed(null, unknown = true, times = RecognitionHealth.WINDOW_SIZE * 2)

        assertTrue("an unknown package must never alarm", reporter.calls.isEmpty())
    }

    @Test
    fun `each platform reports for itself`() {
        val reporter = Recorder()
        val monitor = RecognitionHealthMonitor(reporter, Sink())

        monitor.feed(doordash, unknown = true, times = RecognitionHealth.WINDOW_SIZE)
        monitor.feed(uber, unknown = true, times = RecognitionHealth.WINDOW_SIZE)

        assertEquals(listOf("doordash" to 100, "uber" to 100), reporter.calls)
    }

    @Test
    fun `a throwing reporter never reaches the sensing frame`() {
        val monitor = RecognitionHealthMonitor({ _, _ ->
            throw SecurityException("POST_NOTIFICATIONS not granted")
        }, Sink())

        // Must not throw — this is called from inside the pipeline flow.
        monitor.feed(doordash, unknown = true, times = RecognitionHealth.WINDOW_SIZE * 2)
    }

    @Test fun `recognized frames send rules and UNKNOWN frames send null even with a rule id`() {
        val sink = Sink()
        val monitor = RecognitionHealthMonitor(Recorder(), sink)
        monitor.onScreenAdmitted(doordash, screen(false))
        monitor.onScreenAdmitted(doordash, screen(true))
        assertEquals(listOf(
            ScreenCall(123L, "doordash", "7.1", "doordash.screen.offer"),
            ScreenCall(123L, "doordash", "7.1", null),
        ), sink.screens)
    }

    @Test fun `missing version still feeds local alarm and passes null to sink`() {
        val sink = Sink()
        val reporter = Recorder()
        val monitor = RecognitionHealthMonitor(reporter, sink)
        repeat(RecognitionHealth.WINDOW_SIZE) { monitor.onScreenAdmitted(doordash, screen(true, version = null)) }
        assertEquals(listOf("doordash" to 100), reporter.calls)
        assertTrue(sink.screens.all { it.version == null })
        assertEquals(listOf(ScreenCall(123L, "doordash", null, null)), sink.trips)
    }

    @Test fun `sink failures cannot prevent the alarm WARN or report`() {
        val sink = object : HealthSink {
            override fun onScreen(timestampMillis: Long, platformWire: String, platformAppVersion: String?, ruleId: String?) {
                throw AssertionError("private details must never be logged")
            }
            override fun onTrip(timestampMillis: Long, platformWire: String, platformAppVersion: String?) {
                throw AssertionError("private details must never be logged")
            }
        }
        val logs = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == 5 && tag == "RecognitionHealth") logs += message
            }
        }
        val reporter = Recorder()
        Timber.plant(tree)
        try {
            RecognitionHealthMonitor(reporter, sink).feed(doordash, true, RecognitionHealth.WINDOW_SIZE * 2)
            assertEquals(listOf("doordash" to 100), reporter.calls)
            assertEquals(1, logs.count { it.startsWith("Recognition health:") })
            assertTrue(logs.none { it.contains("private details") })
        } finally {
            Timber.uproot(tree)
        }
    }

    @Test fun `trip edge occurs once per platform per process and recording continues afterwards`() {
        val sink = Sink()
        val monitor = RecognitionHealthMonitor(Recorder(), sink)
        monitor.feed(doordash, true, RecognitionHealth.WINDOW_SIZE * 3)
        monitor.feed(uber, true, RecognitionHealth.WINDOW_SIZE * 3)
        assertEquals(listOf("doordash", "uber"), sink.trips.map { it.platform })
        assertEquals(RecognitionHealth.WINDOW_SIZE * 6, sink.screens.size)
    }

}
