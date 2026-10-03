package cloud.trotter.dashbuddy.core.data.census

import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.census.CensusUploadPreferences
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HttpCensusSinkTest {
    @get:Rule val tmp = TemporaryFolder()
    private class Preferences : CensusUploadPreferences {
        override val enabled = MutableStateFlow(true)
        override val baseUrl = flowOf("https://example.test")
    }
    private class Scheduler : CensusUploadScheduler {
        var runs = 0
        var soon = 0
        override fun enqueueNow() { runs++ }
        override fun enqueueSoon() { soon++ }
        override fun deferUntil(epochMillis: Long) = Unit
    }

    @Test fun `the first item of an empty spool schedules an upload soon and later items do not`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val scheduler = Scheduler()
        val sink = HttpCensusSink(Preferences(), spool, scheduler, stats, backgroundScope, io)
        runCurrent()
        sink.offer(censusRecord(0)); runCurrent()
        assertEquals(1, scheduler.soon)
        repeat(10) { sink.offer(censusRecord(it + 1)) }; runCurrent()
        assertEquals(1, scheduler.soon)
        assertEquals(0, scheduler.runs)
        spool.remove(spool.take(100, 1_000_000).map { it.id }); runCurrent()
        sink.offer(censusRecord(20)); runCurrent()
        assertEquals(2, scheduler.soon)
    }

    @Test(timeout = 5000) fun `offer never waits for a stalled append and overflow drops oldest with counts`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val received = mutableListOf<String>()
        val stats = CensusUploadStats()
        val io = StandardTestDispatcher(testScheduler)
        val spool = object : CensusSpool(tmp.newFolder(), stats, io) {
            override suspend fun append(record: CensusRecord) {
                gate.await()
                received += record.fingerprint
            }
        }
        val prefs = Preferences()
        val sink = HttpCensusSink(prefs, spool, Scheduler(), stats, backgroundScope, io)
        assertFalse(sink.isEnabled)
        runCurrent()
        assertTrue(sink.isEnabled)
        assertTrue(sink.offer(censusRecord(0)))
        runCurrent() // Consumer now waits inside append, not on the producer.
        repeat(300) { assertTrue(sink.offer(censusRecord(it + 1))) }
        assertEquals(44L, stats.spoolDropped.get())
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(0) + (45..300).toList(), received.map { it.toInt(16) })
        prefs.enabled.value = false
        runCurrent()
        assertFalse(sink.isEnabled)
    }

    @Test fun `every fifty appends and one hundred queued items request work`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val scheduler = Scheduler()
        val sink = HttpCensusSink(Preferences(), spool, scheduler, stats, backgroundScope, io)
        runCurrent()
        repeat(99) { sink.offer(censusRecord(it)) }
        runCurrent()
        assertEquals(1, scheduler.runs)
        sink.offer(censusRecord(99))
        runCurrent()
        assertEquals(100, spool.count())
        assertEquals(2, scheduler.runs)
        sink.offer(censusRecord(100))
        runCurrent()
        assertEquals(3, scheduler.runs)
    }
}
