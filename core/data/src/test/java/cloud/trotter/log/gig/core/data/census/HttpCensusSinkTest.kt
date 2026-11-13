package cloud.trotter.log.gig.core.data.census

import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.NotificationSkeletonSchema
import cloud.trotter.log.gig.domain.capture.CensusRecord
import cloud.trotter.log.gig.domain.census.CensusChoices
import cloud.trotter.log.gig.domain.census.CensusUploadPreferences
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.state.Platform
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
        override val acceptedSchemaIds = MutableStateFlow(setOf(SkeletonSchema.SCHEMA_ID))
        override val choices = MutableStateFlow(CensusChoices(null, wifiOnly = true))
    }

    private fun sample() = CensusDailySample(java.io.File(tmp.newFolder(), "sampled.json"), kotlinx.coroutines.Dispatchers.Unconfined)

    private fun kotlinx.coroutines.test.TestScope.sink(
        prefs: Preferences,
        spool: CensusSpool,
        stats: CensusUploadStats,
        io: kotlinx.coroutines.CoroutineDispatcher,
    ) = HttpCensusSink(prefs, spool, sample(), stats, backgroundScope, io, readsUnboundPlatforms = false)

    @Test fun `publisher schema snapshot follows policy including an empty accepted set`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val prefs = Preferences()
        val sink = sink(prefs, CensusSpool(tmp.newFolder(), stats, io), stats, io)
        assertFalse(sink.isEnabled)
        runCurrent()
        assertTrue(sink.isEnabled)
        assertEquals(setOf(SkeletonSchema.SCHEMA_ID), sink.acceptedSchemaIds)
        prefs.acceptedSchemaIds.value = setOf(NotificationSkeletonSchema.SCHEMA_ID)
        runCurrent()
        assertEquals(setOf(NotificationSkeletonSchema.SCHEMA_ID), sink.acceptedSchemaIds)
        prefs.acceptedSchemaIds.value = emptySet()
        runCurrent()
        assertTrue(sink.isEnabled) // Publisher counts the refusal even when no schema is advertised.
        assertEquals(emptySet<String>(), sink.acceptedSchemaIds)
    }

    @Test fun `without consent nothing is queued`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val prefs = Preferences().apply { enabled.value = false }
        val sink = sink(prefs, spool, stats, io)
        runCurrent()
        assertFalse(sink.isEnabled)
        repeat(5) { sink.offer(censusRecord(it)) }; runCurrent()
        assertEquals(0, spool.count())
        assertEquals(5L, stats.spoolDropped.get())
    }

    @Test fun `only the delivery apps the choices cover are queued`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val prefs = Preferences().apply { choices.value = CensusChoices(setOf(Platform.Uber), wifiOnly = true) }
        val sink = sink(prefs, spool, stats, io)
        runCurrent()
        sink.offer(censusRecord(1)); runCurrent()
        assertEquals("a DoorDash skeleton is left out", 0, spool.count())
        assertEquals(1L, stats.platformNotChosen.get())
        prefs.choices.value = CensusChoices(setOf(Platform.DoorDash), wifiOnly = true)
        sink.offer(censusRecord(1)); runCurrent()
        assertEquals(1, spool.count())
    }

    @Test fun `one skeleton per variant per UTC day`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val sink = sink(Preferences(), spool, stats, io)
        runCurrent()
        repeat(3) { sink.offer(censusRecord(7)) }
        sink.offer(censusRecord(8))
        sink.offer(censusRecord(7, day = "2026-10-03"))
        runCurrent()
        assertEquals("one of each variant a day, the next day again", 3, spool.count())
        assertEquals(2L, stats.sampledRepeat.get())
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
        val sink = sink(prefs, spool, stats, io)
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

    @Test fun `both kinds enter one spool, sampled alike`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val stats = CensusUploadStats()
        val spool = CensusSpool(tmp.newFolder(), stats, io)
        val sink = sink(Preferences(), spool, stats, io)
        runCurrent()
        repeat(50) { index ->
            assertTrue(sink.offer(if (index % 2 == 0) notificationCensusRecord() else censusRecord(index)))
        }
        runCurrent()
        val items = spool.take(100, 1_000_000)
        assertEquals(26, items.size)
        assertEquals(1, items.count { it.itemJson == notificationCensusRecord().skeletonJson })
        assertEquals(26L, stats.spooled.get())
        assertEquals(24L, stats.sampledRepeat.get())
    }

}
