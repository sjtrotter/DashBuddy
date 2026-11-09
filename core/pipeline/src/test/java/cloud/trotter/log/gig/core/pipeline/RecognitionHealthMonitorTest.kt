package cloud.trotter.log.gig.core.pipeline

import cloud.trotter.log.gig.domain.census.HealthSink
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ParseShortfall
import cloud.trotter.log.gig.domain.pipeline.RecognitionHealthReporter
import cloud.trotter.log.gig.domain.pipeline.RecognitionIncident
import cloud.trotter.log.gig.domain.pipeline.RecognitionNoticeStore
import cloud.trotter.log.gig.domain.pipeline.UNKNOWN_TARGET
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.io.IOException

/**
 * #937 + #1560 — the side-effecting edge of recognition health: the one incident owner per platform.
 *
 * The pure decisions have their own tests ([RecognitionHealthTest], [ParseRotTrackerTest],
 * [RecognitionIncidentBookTest]); this one covers what the monitor adds: package→platform resolution
 * through the registry, one notice per incident for both causes, the last-notice persistence and its
 * ordering, and — the property that actually matters on a dash — fail-open. This runs once per
 * admitted frame, so a broken reporter or store must cost a log line, never a frame.
 *
 * The monitor's action consumer never completes, so it runs in `backgroundScope`; `io` is an
 * [UnconfinedTestDispatcher] (the load and the consumer run eagerly) unless a case needs to hold an
 * interleaving, where it is a [StandardTestDispatcher].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecognitionHealthMonitorTest {

    /**
     * Every reporter call that changes the shade, in order: `notice:<wire>` / `closed:<wire>`. Like the real notification
     * shade, withdrawing a notice that is not there is a no-op and is not recorded; [shade] starts with the notices a
     * previous process left behind.
     */
    private class Recorder(
        val calls: MutableList<String> = mutableListOf(),
        private val shade: MutableSet<String> = mutableSetOf(),
    ) : RecognitionHealthReporter {
        val notices = mutableListOf<RecognitionIncident>()
        override fun onIncidentNotice(incident: RecognitionIncident) {
            calls += "notice:${incident.platformWire}"
            notices += incident
            shade += incident.platformWire
        }
        override fun onIncidentClosed(platformWire: String) {
            if (shade.remove(platformWire)) calls += "closed:$platformWire"
        }
    }

    private class Store(
        private val persisted: Map<String, Long> = emptyMap(),
        private val loadGate: CompletableDeferred<Unit>? = null,
        private val saveGate: CompletableDeferred<Unit>? = null,
        private val loadFailure: Throwable? = null,
        private val saveFailure: Throwable? = null,
        private val events: MutableList<String>? = null,
    ) : RecognitionNoticeStore {
        val saves = mutableListOf<Map<String, Long>>()
        override suspend fun load(): Map<String, Long> {
            loadGate?.await()
            loadFailure?.let { throw it }
            return persisted
        }
        override suspend fun save(lastNoticeAt: Map<String, Long>) {
            saveGate?.await()
            saveFailure?.let { throw it }
            saves += lastNoticeAt
            events?.add("save")
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

    /** WARN lines under the monitor's tag. */
    private val warnings = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (priority == 5 && tag == "RecognitionHealth") warnings += message
        }
    }

    @Before fun plant() = Timber.plant(tree)

    @After fun uproot() = Timber.uproot(tree)

    private fun screen(unknown: Boolean, version: String? = "7.1") = Observation.Screen(
        timestamp = 123L, captureId = null, ruleId = "doordash.screen.offer",
        metadata = ReplayMetadata.EMPTY.copy(platformAppVersion = version),
        flow = null, modeHint = null, parsed = ParsedFields.None,
        target = if (unknown) UNKNOWN_TARGET else "offer",
    )

    private val offerRule = "doordash.screen.offer_card"
    private val receiptRule = "doordash.screen.delivery_receipt"
    private val homeRule = "doordash.screen.home"
    private val offerRot = ParseShortfall(
        ruleId = offerRule, shape = "offer", nullRequiredFields = listOf("payAmount", "distance"), branchSkipped = true,
    )
    private val receiptRot = ParseShortfall(ruleId = receiptRule, shape = "post_task", nullRequiredFields = listOf("totalPay"))

    private fun frame(
        t: Long,
        target: String = UNKNOWN_TARGET,
        ruleId: String? = null,
        shortfalls: List<ParseShortfall> = emptyList(),
    ) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = ruleId, metadata = ReplayMetadata.EMPTY,
        flow = null, modeHint = null, parsed = ParsedFields.None, target = target, parseShortfalls = shortfalls,
    )

    private val doordash = requireNotNull(Platform.DoorDash.packageName)
    private val uber = requireNotNull(Platform.Uber.packageName)
    private val dd = Platform.DoorDash.wire

    private val hour = 60 * 60 * 1000L
    private val day = RecognitionIncidentBook.NOTICE_INTERVAL_MS

    private fun TestScope.monitor(
        reporter: RecognitionHealthReporter = Recorder(),
        sink: HealthSink = Sink(),
        store: RecognitionNoticeStore = Store(),
        io: CoroutineDispatcher = UnconfinedTestDispatcher(testScheduler),
    ) = RecognitionHealthMonitor(reporter, sink, store, backgroundScope, io)

    private fun RecognitionHealthMonitor.feed(pkg: String?, unknown: Boolean, times: Int) {
        repeat(times) { onScreenAdmitted(pkg, screen(unknown)) }
    }

    /** An UNKNOWN frame carrying the offer rule's skipped-branch rot. */
    private fun RecognitionHealthMonitor.rotVisit(t: Long) =
        onScreenAdmitted(doordash, frame(t, shortfalls = listOf(offerRot)))

    /** A recognized frame of another rule: ends a visit without feeding the UNKNOWN rate. */
    private fun RecognitionHealthMonitor.separator(t: Long) =
        onScreenAdmitted(doordash, frame(t, target = "home", ruleId = homeRule))

    private fun RecognitionHealthMonitor.healthyOffer(t: Long) =
        onScreenAdmitted(doordash, frame(t, target = "offer", ruleId = offerRule))

    /** Five offer rot visits 4 s apart, each followed by a separator; the frame at [tripAt] trips the rule. */
    private fun RecognitionHealthMonitor.tripOffer(tripAt: Long) {
        val start = tripAt - 19_000
        for (i in 0 until 5) {
            val t = start + i * 4_000L
            rotVisit(t)
            separator(t + 1_000)
        }
        separator(tripAt)
    }

    // ── #937, kept: the UNKNOWN cause ───────────────────────────────────

    @Test
    fun `a collapsed platform is reported once, by wire`() = runTest {
        val reporter = Recorder()
        val monitor = monitor(reporter)

        monitor.feed(doordash, unknown = true, times = RecognitionHealth.WINDOW_SIZE * 3)
        runCurrent()

        assertEquals(listOf("notice:doordash"), reporter.calls)
        val incident = reporter.notices.single()
        assertTrue(incident.screensUnrecognized)
        assertTrue(incident.parseRot.isEmpty())
        assertEquals("the percentage stays in the #937 WARN", 1, warnings.count { it.contains("100%") })
    }

    @Test
    fun `a healthy platform is never reported`() = runTest {
        val reporter = Recorder()
        val monitor = monitor(reporter)

        repeat(RecognitionHealth.WINDOW_SIZE * 4) { i ->
            monitor.onScreenAdmitted(doordash, screen(unknown = i % 6 == 0))
        }
        runCurrent()

        assertTrue(reporter.calls.isEmpty())
        assertTrue(monitor.open.value.isEmpty())
    }

    @Test
    fun `an unattributable frame is ignored - no platform, no recognition contract`() = runTest {
        val reporter = Recorder()
        val monitor = monitor(reporter)

        monitor.feed("com.android.settings", unknown = true, times = RecognitionHealth.WINDOW_SIZE * 2)
        monitor.feed(null, unknown = true, times = RecognitionHealth.WINDOW_SIZE * 2)
        runCurrent()

        assertTrue("an unknown package must never alarm", reporter.calls.isEmpty())
    }

    @Test
    fun `each platform reports for itself`() = runTest {
        val reporter = Recorder()
        val monitor = monitor(reporter)

        monitor.feed(doordash, unknown = true, times = RecognitionHealth.WINDOW_SIZE)
        monitor.feed(uber, unknown = true, times = RecognitionHealth.WINDOW_SIZE)
        runCurrent()

        assertEquals(listOf("notice:doordash", "notice:uber"), reporter.calls)
        assertEquals(listOf("doordash", "uber"), monitor.open.value.map { it.platformWire })
    }

    @Test
    fun `a throwing reporter never reaches the sensing frame`() = runTest {
        val reporter = object : RecognitionHealthReporter {
            override fun onIncidentNotice(incident: RecognitionIncident) {
                throw SecurityException("POST_NOTIFICATIONS not granted")
            }
            override fun onIncidentClosed(platformWire: String) {
                throw SecurityException("POST_NOTIFICATIONS not granted")
            }
        }
        val monitor = monitor(reporter)

        // Must not throw — this is called from inside the pipeline flow.
        monitor.feed(doordash, unknown = true, times = RecognitionHealth.WINDOW_SIZE * 2)
        runCurrent()
    }

    @Test fun `recognized frames send rules and UNKNOWN frames send null even with a rule id`() = runTest {
        val sink = Sink()
        val monitor = monitor(sink = sink)
        monitor.onScreenAdmitted(doordash, screen(false))
        monitor.onScreenAdmitted(doordash, screen(true))
        assertEquals(listOf(
            ScreenCall(123L, "doordash", "7.1", "doordash.screen.offer"),
            ScreenCall(123L, "doordash", "7.1", null),
        ), sink.screens)
    }

    @Test fun `missing version still feeds local alarm and passes null to sink`() = runTest {
        val sink = Sink()
        val reporter = Recorder()
        val monitor = monitor(reporter, sink)
        repeat(RecognitionHealth.WINDOW_SIZE) { monitor.onScreenAdmitted(doordash, screen(true, version = null)) }
        runCurrent()
        assertEquals(listOf("notice:doordash"), reporter.calls)
        assertTrue(sink.screens.all { it.version == null })
        assertEquals(listOf(ScreenCall(123L, "doordash", null, null)), sink.trips)
    }

    @Test fun `sink failures cannot prevent the alarm WARN or report`() = runTest {
        val sink = object : HealthSink {
            override fun onScreen(timestampMillis: Long, platformWire: String, platformAppVersion: String?, ruleId: String?) {
                throw AssertionError("private details must never be logged")
            }
            override fun onTrip(timestampMillis: Long, platformWire: String, platformAppVersion: String?) {
                throw AssertionError("private details must never be logged")
            }
        }
        val reporter = Recorder()
        monitor(reporter, sink).feed(doordash, true, RecognitionHealth.WINDOW_SIZE * 2)
        runCurrent()
        assertEquals(listOf("notice:doordash"), reporter.calls)
        assertEquals(1, warnings.count { it.startsWith("Recognition health:") })
        assertTrue(warnings.none { it.contains("private details") })
    }

    @Test fun `trip edge occurs once per platform per process and recording continues afterwards`() = runTest {
        val sink = Sink()
        val monitor = monitor(sink = sink)
        monitor.feed(doordash, true, RecognitionHealth.WINDOW_SIZE * 3)
        monitor.feed(uber, true, RecognitionHealth.WINDOW_SIZE * 3)
        assertEquals(listOf("doordash", "uber"), sink.trips.map { it.platform })
        assertEquals(RecognitionHealth.WINDOW_SIZE * 6, sink.screens.size)
    }

    // ── #1560: one incident owner ───────────────────────────────────────

    // 1
    @Test
    fun `one update posts one notice for both causes`() = runTest {
        val reporter = Recorder()
        val sink = Sink()
        val monitor = monitor(reporter, sink)

        // Alternating offer rot frames and plain UNKNOWN frames, 1 s apart: the rot trips at frame 20
        // (occurrences start at 0, 4, 8, 12 and 16 s), the UNKNOWN alarm at frame 50.
        for (i in 0 until 60) {
            val shortfalls = if (i % 2 == 0) listOf(offerRot) else emptyList()
            monitor.onScreenAdmitted(doordash, frame(i * 1_000L, shortfalls = shortfalls))
            if (i == 19) {
                runCurrent()
                assertEquals("the rot trip posts at frame 20", listOf("notice:doordash"), reporter.calls)
            }
        }
        runCurrent()

        assertEquals(listOf("notice:doordash"), reporter.calls)
        val incident = monitor.open.value.single()
        assertTrue(incident.screensUnrecognized)
        assertEquals(listOf(offerRule), incident.parseRot.map { it.ruleId })
        assertEquals(1, sink.trips.size)
    }

    // 2
    @Test
    fun `a restart posts no second notice within a day and withdraws the stale one`() = runTest {
        val t0 = 10 * day
        val reporter = Recorder(shade = mutableSetOf(dd))
        val store = Store(persisted = mapOf(dd to t0))
        val monitor = monitor(reporter, store = store)
        runCurrent()

        assertEquals(listOf("closed:doordash"), reporter.calls)
        assertTrue(monitor.open.value.isEmpty())

        monitor.tripOffer(t0 + 2 * hour)
        runCurrent()
        assertNotNull(monitor.open.value.singleOrNull())
        assertEquals("no notice inside the day", listOf("closed:doordash"), reporter.calls)

        monitor.separator(t0 + day + 1_000)
        runCurrent()
        assertEquals(listOf("closed:doordash", "notice:doordash"), reporter.calls)
        assertEquals(mapOf(dd to t0 + day + 1_000), store.saves.last())
    }

    // 2b (Sol r1 S1): a notice that posted after a failed save left no trace in the store.
    @Test
    fun `a restart withdraws a leftover notice the store never recorded`() = runTest {
        val reporter = Recorder(shade = mutableSetOf(dd))
        val monitor = monitor(reporter, store = Store())
        runCurrent()

        assertEquals(listOf("closed:doordash"), reporter.calls)
        assertTrue(monitor.open.value.isEmpty())
    }

    // 3
    @Test
    fun `the time is written before the notice posts`() = runTest {
        val reporter = Recorder()
        val monitor = monitor(
            reporter,
            store = Store(saveGate = CompletableDeferred()),
            io = StandardTestDispatcher(testScheduler),
        )
        runCurrent() // the load

        monitor.tripOffer(50_000)
        runCurrent()
        assertTrue("the post waits for the write", reporter.calls.isEmpty())

        advanceTimeBy(RecognitionHealthMonitor.SAVE_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(listOf("notice:doordash"), reporter.calls)
        assertEquals(1, warnings.count { it.contains("save failed (timeout)") })
    }

    @Test
    fun `a completed write is observed before the notice`() = runTest {
        val events = mutableListOf<String>()
        val monitor = monitor(Recorder(events), store = Store(events = events))

        monitor.tripOffer(50_000)
        runCurrent()

        assertEquals(listOf("save", "notice:doordash"), events)
    }

    // 4
    @Test
    fun `a load failure fails open`() = runTest {
        val reporter = Recorder()
        val monitor = monitor(reporter, store = Store(loadFailure = IOException("disk")))

        monitor.tripOffer(50_000)
        runCurrent()

        assertEquals(listOf("notice:doordash"), reporter.calls)
        assertEquals(1, warnings.count { it.contains("load failed") })
    }

    @Test
    fun `a save failure fails open`() = runTest {
        val reporter = Recorder()
        val monitor = monitor(reporter, store = Store(saveFailure = IOException("disk")))

        monitor.tripOffer(50_000)
        for (i in 1..89) monitor.separator(50_000L + i * 1_000L) // 100 frames in all, each returns normally
        runCurrent()

        assertEquals(listOf("notice:doordash"), reporter.calls)
        assertEquals(1, warnings.count { it.contains("save failed") })
    }

    @Test
    fun `a load that never returns times out and fails open`() = runTest {
        val reporter = Recorder()
        val monitor = monitor(
            reporter,
            store = Store(loadGate = CompletableDeferred()),
            io = StandardTestDispatcher(testScheduler),
        )
        runCurrent()

        advanceTimeBy(RecognitionHealthMonitor.LOAD_TIMEOUT_MS + 1)
        runCurrent()
        assertEquals(1, warnings.count { it.contains("load failed (timeout)") })

        monitor.tripOffer(50_000)
        runCurrent()
        assertEquals(listOf("notice:doordash"), reporter.calls)
    }

    // 5
    @Test
    fun `muted notifications still record the incident and never retry`() = runTest {
        var calls = 0
        val reporter = object : RecognitionHealthReporter {
            override fun onIncidentNotice(incident: RecognitionIncident) {
                calls++
                throw SecurityException("POST_NOTIFICATIONS not granted")
            }
            override fun onIncidentClosed(platformWire: String) = Unit
        }
        val monitor = monitor(reporter)

        monitor.tripOffer(50_000)
        runCurrent()
        assertNotNull(monitor.open.value.single().notifiedAt)

        for (i in 0 until 20) {
            val t = 60_000L + i * 4_000L
            monitor.rotVisit(t)
            monitor.separator(t + 1_000)
        }
        runCurrent()

        assertEquals(1, calls)
        assertNotNull(monitor.open.value.single().notifiedAt)
    }

    // 6
    @Test
    fun `notices wait for the load`() = runTest {
        val reporter = Recorder()
        val gate = CompletableDeferred<Unit>()
        val monitor = monitor(reporter, store = Store(loadGate = gate))

        monitor.tripOffer(50_000)
        runCurrent()
        assertTrue(reporter.calls.isEmpty())
        assertNull(monitor.open.value.single().notifiedAt)

        gate.complete(Unit)
        runCurrent()
        assertTrue("an empty store withdraws nothing", reporter.calls.isEmpty())

        monitor.separator(51_000)
        runCurrent()
        assertEquals(listOf("notice:doordash"), reporter.calls)
    }

    // 7
    @Test
    fun `a close stays behind its post`() = runTest {
        val reporter = Recorder()
        val gate = CompletableDeferred<Unit>()
        val monitor = monitor(reporter, store = Store(saveGate = gate))

        monitor.tripOffer(50_000)
        monitor.healthyOffer(51_000)
        monitor.healthyOffer(52_000)
        monitor.healthyOffer(53_000)
        runCurrent()
        assertTrue(monitor.open.value.isEmpty())
        assertTrue("both wait behind the write", reporter.calls.isEmpty())

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("notice:doordash", "closed:doordash"), reporter.calls)
    }

    // 8
    @Test
    fun `a frame that trips one rule and clears another posts no close`() = runTest {
        val reporter = Recorder()
        val monitor = monitor(reporter)

        monitor.tripOffer(19_000)
        monitor.healthyOffer(20_500)
        monitor.healthyOffer(21_000)
        // The receipt rule: four confirmed visits and a fifth pending since 37 500.
        for (i in 0 until 5) {
            val t = 21_500L + i * 4_000L
            monitor.onScreenAdmitted(
                doordash,
                frame(t, target = "delivery_receipt", ruleId = receiptRule, shortfalls = listOf(receiptRot)),
            )
            monitor.separator(t + 1_000)
        }
        // One frame confirms the receipt's fifth visit (a trip) and is the offer's third healthy parse (a clear).
        monitor.healthyOffer(40_500)
        runCurrent()

        assertEquals(listOf("notice:doordash"), reporter.calls)
        val incident = monitor.open.value.single()
        assertEquals(listOf(receiptRule), incident.parseRot.map { it.ruleId })
        assertFalse(incident.screensUnrecognized)
    }

    // 9a
    @Test
    fun `an incident open before the load keeps its notice and gets no close`() = runTest {
        val t0 = 10 * day
        val reporter = Recorder(shade = mutableSetOf(dd))
        val gate = CompletableDeferred<Unit>()
        val monitor = monitor(
            reporter,
            store = Store(persisted = mapOf(dd to t0 - 25 * hour), loadGate = gate),
            io = StandardTestDispatcher(testScheduler),
        )

        monitor.tripOffer(t0)
        gate.complete(Unit)
        runCurrent()
        monitor.separator(t0 + 1_000)
        runCurrent()

        assertEquals(listOf("notice:doordash"), reporter.calls)
    }

    // 9b
    @Test
    fun `an incident open right after the load posts after the stale close`() = runTest {
        val t0 = 10 * day
        val reporter = Recorder(shade = mutableSetOf(dd))
        val gate = CompletableDeferred<Unit>()
        val monitor = monitor(
            reporter,
            store = Store(persisted = mapOf(dd to t0 - 25 * hour), loadGate = gate),
            io = StandardTestDispatcher(testScheduler),
        )

        gate.complete(Unit)
        runCurrent()
        monitor.tripOffer(t0)
        runCurrent()

        assertEquals(listOf("closed:doordash", "notice:doordash"), reporter.calls)
    }
}
