package cloud.trotter.dashbuddy.core.pipeline

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

    private val doordash = requireNotNull(Platform.DoorDash.packageName)
    private val uber = requireNotNull(Platform.Uber.packageName)

    private fun RecognitionHealthMonitor.feed(pkg: String?, unknown: Boolean, times: Int) {
        repeat(times) { onScreenAdmitted(pkg, unknown) }
    }

    @Test
    fun `a collapsed platform is reported once, by wire, with its percentage`() {
        val reporter = Recorder()
        val monitor = RecognitionHealthMonitor(reporter)

        monitor.feed(doordash, unknown = true, times = RecognitionHealth.WINDOW_SIZE * 3)

        assertEquals(listOf("doordash" to 100), reporter.calls)
    }

    @Test
    fun `a healthy platform is never reported`() {
        val reporter = Recorder()
        val monitor = RecognitionHealthMonitor(reporter)

        repeat(RecognitionHealth.WINDOW_SIZE * 4) { i ->
            monitor.onScreenAdmitted(doordash, unknown = i % 6 == 0)
        }

        assertTrue(reporter.calls.isEmpty())
    }

    @Test
    fun `an unattributable frame is ignored - no platform, no recognition contract`() {
        val reporter = Recorder()
        val monitor = RecognitionHealthMonitor(reporter)

        monitor.feed("com.android.settings", unknown = true, times = RecognitionHealth.WINDOW_SIZE * 2)
        monitor.feed(null, unknown = true, times = RecognitionHealth.WINDOW_SIZE * 2)

        assertTrue("an unknown package must never alarm", reporter.calls.isEmpty())
    }

    @Test
    fun `each platform reports for itself`() {
        val reporter = Recorder()
        val monitor = RecognitionHealthMonitor(reporter)

        monitor.feed(doordash, unknown = true, times = RecognitionHealth.WINDOW_SIZE)
        monitor.feed(uber, unknown = true, times = RecognitionHealth.WINDOW_SIZE)

        assertEquals(listOf("doordash" to 100, "uber" to 100), reporter.calls)
    }

    @Test
    fun `a throwing reporter never reaches the sensing frame`() {
        val monitor = RecognitionHealthMonitor { _, _ ->
            throw SecurityException("POST_NOTIFICATIONS not granted")
        }

        // Must not throw — this is called from inside the pipeline flow.
        monitor.feed(doordash, unknown = true, times = RecognitionHealth.WINDOW_SIZE * 2)
    }
}
