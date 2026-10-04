package cloud.trotter.dashbuddy.core.data.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.census.contract.SensitiveMarkerScan
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.census.EnvelopeProjection
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import timber.log.Timber
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentCensusEnvelopeSinkTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class Harness(scope: TestScope) {
        val io = StandardTestDispatcher(scope.testScheduler)
        val preferences = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), true, io)
        val stats = CensusUploadStats()
        val spool: CensusSpool = mock()
        val records = mutableListOf<CensusRecord>()
        val sink = PersistentCensusEnvelopeSink(preferences, spool, stats, scope.backgroundScope)
        suspend fun enable() {
            whenever(spool.append(any())).thenAnswer { records += it.getArgument<CensusRecord>(0); Unit }
            preferences.setCensusUploadEnabled(true)
            preferences.setCensusShareCaptures(true)
        }
    }
    private val fingerprint = "abcdef".repeat(10) + "abcd"
    private fun envelope(payload: String = "{\"text\":\"Continue\"}") =
        """{"schemaId":"uinode.v1","timestamp":7200123,"platform":"doordash","metadata":{"deviceFingerprint":"Transfer to bank","rulesetSignature":"signature"},"payload":$payload}"""

    @Test fun `both switches gate hold pair and pending entries are cleared when disabled`() = runTest {
        val h = Harness(this)
        runCurrent()
        h.sink.hold("off", Platform.DoorDash, envelope())
        assertFalse(h.sink.pair("off", fingerprint))
        h.preferences.setCensusUploadEnabled(true)
        runCurrent()
        assertFalse(h.sink.isEnabled)
        h.enable()
        runCurrent()
        assertTrue(h.sink.isEnabled)
        h.sink.hold("held", Platform.DoorDash, envelope())
        h.preferences.setCensusUploadEnabled(false)
        runCurrent()
        assertFalse(h.sink.isEnabled)
        h.enable()
        runCurrent()
        assertFalse(h.sink.pair("held", fingerprint))
        assertEquals(1L, h.stats.envelopesHeld.get())
    }

    @Test fun `pairs exact capture once projects before scanning and writes asynchronously`() = runTest {
        val h = Harness(this)
        h.enable()
        runCurrent()
        h.sink.hold("first", Platform.DoorDash, envelope())
        h.sink.hold("second", Platform.DoorDash, envelope("{\"text\":\"Next\"}"))
        assertFalse(h.sink.pair("absent", fingerprint))
        assertTrue(h.sink.pair("first", fingerprint))
        assertFalse(h.sink.pair("first", fingerprint))
        assertTrue(h.records.isEmpty())
        runCurrent()
        val record = h.records.single()
        assertEquals("first", record.captureId)
        assertEquals(fingerprint, record.fingerprint)
        assertEquals(EnvelopeProjection.project(envelope(), fingerprint), record.skeletonJson)
        assertEquals(record.skeletonJson.toByteArray(Charsets.UTF_8).size, record.itemBytes)
        assertEquals(1L, h.stats.envelopesSpooled.get())
        assertTrue(h.stats.summary().contains("envelopesSpooled=1"))
    }

    @Test fun `oldest unpaired entry is silently evicted at sixteen`() = runTest {
        val h = Harness(this)
        h.enable()
        runCurrent()
        repeat(17) { h.sink.hold("id-$it", Platform.DoorDash, envelope()) }
        assertFalse(h.sink.pair("id-0", fingerprint))
        assertTrue(h.sink.pair("id-1", fingerprint))
        assertTrue(h.sink.pair("id-16", fingerprint))
        assertEquals(0L, h.stats.envelopesDropped.get())
    }

    @Test fun `scan checks keys nested arrays and decoded escaped values and refuses excessive depth`() = runTest {
        val h = Harness(this)
        h.enable()
        runCurrent()
        for (payload in listOf("{\"Transfer to bank\":0}", "{\"children\":[{\"text\":\"Transfer to bank\"}]}" ,
            "{\"text\":\"Transfer to \\u0062ank\"}")) {
            h.sink.hold("id", Platform.DoorDash, envelope(payload))
            assertTrue(h.sink.pair("id", fingerprint))
        }
        h.sink.hold("deep", Platform.DoorDash, envelope("[".repeat(66) + "0" + "]".repeat(66)))
        assertTrue(h.sink.pair("deep", fingerprint))
        h.sink.hold("invalid", Platform.DoorDash, "{}")
        assertTrue(h.sink.pair("invalid", fingerprint))
        assertEquals(0L, h.stats.envelopesSensitiveDropped.get())
        assertEquals(0L, h.stats.envelopesProjectionRefused.get())
        runCurrent()
        assertEquals(3L, h.stats.envelopesSensitiveDropped.get())
        assertEquals(2L, h.stats.envelopesProjectionRefused.get())
        assertTrue(h.records.isEmpty())
    }

    @Test fun `bounded channel drops oldest and counts overflow then discards when sharing stops`() = runTest {
        val h = Harness(this)
        h.enable()
        runCurrent()
        // One receiver can already own a record; enough offers fill the 64 slots and overflow it.
        repeat(70) {
            h.sink.hold("id-$it", Platform.DoorDash, envelope())
            assertTrue(h.sink.pair("id-$it", fingerprint))
        }
        assertTrue(h.stats.envelopesDropped.get() >= 5)
        runCurrent()
        assertEquals(70L, h.stats.envelopesDropped.get() + h.stats.envelopesSpooled.get())
        assertEquals("id-69", h.records.last().captureId)
        h.preferences.setCensusShareCaptures(false)
        runCurrent()
        h.sink.hold("off", Platform.DoorDash, envelope())
        assertFalse(h.sink.pair("off", fingerprint))
    }

    @Test fun `invalidate discards held and queued generations before consumer resumes and permits fresh pairs`() = runTest {
        val h = Harness(this)
        h.enable()
        runCurrent()
        clearInvocations(h.spool)
        h.sink.hold("held", Platform.DoorDash, envelope())
        h.sink.hold("queued", Platform.DoorDash, envelope())
        assertTrue(h.sink.pair("queued", fingerprint))
        // The StandardTestDispatcher has not resumed the consumer after trySend.
        h.sink.invalidate()
        verify(h.spool).clear()
        runCurrent()
        assertFalse(h.sink.pair("held", fingerprint))
        verify(h.spool, never()).append(any())
        assertTrue(h.records.isEmpty())
        assertEquals(1L, h.stats.envelopesDropped.get())
        h.sink.hold("fresh", Platform.DoorDash, envelope())
        assertTrue(h.sink.pair("fresh", fingerprint))
        runCurrent()
        assertEquals("fresh", h.records.single().captureId)
        assertEquals(1L, h.stats.envelopesSpooled.get())
    }

    @Test fun `invalidate waits for active writer clears its result and drops the next queued record`() = runTest {
        val io = StandardTestDispatcher(testScheduler)
        val preferences = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), true, io)
        val stats = CensusUploadStats()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val records = mutableListOf<CensusRecord>()
        val appended = mutableListOf<String?>()
        var clears = 0
        val spool = object : CensusSpool(File("unused"), stats, io) {
            override suspend fun append(record: CensusRecord) {
                started.complete(Unit)
                release.await()
                records += record
                appended += record.captureId
            }
            override suspend fun clear() { clears++; records.clear() }
        }
        preferences.setCensusUploadEnabled(true)
        preferences.setCensusShareCaptures(true)
        val sink = PersistentCensusEnvelopeSink(preferences, spool, stats, backgroundScope)
        runCurrent()
        sink.hold("writing", Platform.DoorDash, envelope())
        assertTrue(sink.pair("writing", fingerprint))
        runCurrent()
        assertTrue(started.isCompleted)
        sink.hold("queued", Platform.DoorDash, envelope())
        assertTrue(sink.pair("queued", fingerprint))
        val invalidation = launch { sink.invalidate() }
        runCurrent()
        assertFalse(invalidation.isCompleted)
        assertEquals(0, clears)
        release.complete(Unit)
        runCurrent()
        invalidation.join()
        assertEquals(1, clears)
        assertTrue(records.isEmpty())
        assertEquals(listOf("writing"), appended)
        assertEquals(1L, stats.envelopesDropped.get())
        sink.hold("fresh", Platform.DoorDash, envelope())
        assertTrue(sink.pair("fresh", fingerprint))
        runCurrent()
        assertEquals("fresh", records.single().captureId)
    }

    @Test fun `either switch off clears a previously spooled envelope`() = runTest {
        for (consent in listOf(false, true)) {
            val h = Harness(this)
            h.enable()
            runCurrent()
            whenever(h.spool.clear()).thenAnswer { h.records.clear(); Unit }
            clearInvocations(h.spool)
            h.sink.hold("spooled", Platform.DoorDash, envelope())
            assertTrue(h.sink.pair("spooled", fingerprint))
            runCurrent()
            assertEquals(1, h.records.size)
            if (consent) h.preferences.setCensusUploadEnabled(false) else h.preferences.setCensusShareCaptures(false)
            runCurrent()
            verify(h.spool).clear()
            assertTrue(h.records.isEmpty())
            assertFalse(h.sink.isEnabled)
        }
    }

    @Test fun `a failing clear at start-up or on the off edge is counted and never kills the collector`() = runTest {
        val h = Harness(this)
        // Start-up with both switches OFF: the first (false) emission runs the housekeeping clear, and it fails.
        whenever(h.spool.clear()).thenAnswer { throw java.io.IOException("storage_full") }
        runCurrent()
        assertEquals(1, h.stats.envelopesDropped.get())
        // The collector is alive: enabling still turns the sink on, and the OFF edge's failing clear is absorbed too.
        h.enable()
        runCurrent()
        assertTrue(h.sink.isEnabled)
        h.preferences.setCensusShareCaptures(false)
        runCurrent()
        assertFalse(h.sink.isEnabled)
        assertEquals(2, h.stats.envelopesDropped.get())
        // An explicit identity-boundary call still propagates the failure to its caller.
        var thrown = false
        try { h.sink.invalidate() } catch (_: java.io.IOException) { thrown = true }
        assertTrue(thrown)
    }

    @Test fun `projection cap is enforced by the consumer after serialization`() = runTest {
        val h = Harness(this)
        h.enable()
        runCurrent()
        h.sink.hold("large", Platform.DoorDash, envelope("\"${"x".repeat(EnvelopeProjection.MAX_BYTES)}\""))
        assertTrue(h.sink.pair("large", fingerprint))
        assertEquals(0L, h.stats.envelopesProjectionRefused.get())
        runCurrent()
        assertEquals(1L, h.stats.envelopesProjectionRefused.get())
        verify(h.spool, never()).append(any())
    }

    @Test fun `marker warning survives scanning and is emitted once per sink instance`() = runTest {
        val warnings = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == 5 && tag == "Census") warnings += message
            }
        }
        Timber.plant(tree)
        try {
            repeat(2) {
                val h = Harness(this)
                h.enable()
                runCurrent()
                repeat(2) {
                    h.sink.hold("sensitive", Platform.DoorDash, envelope("{\"text\":\"Transfer to bank\"}"))
                    assertTrue(h.sink.pair("sensitive", fingerprint))
                }
                runCurrent()
                assertEquals(2L, h.stats.envelopesSensitiveDropped.get())
                assertTrue(h.records.isEmpty())
            }
            assertEquals(List(2) { "census envelope dropped markerId=Tr16" }, warnings)
            warnings.forEach { assertNull(SensitiveMarkerScan.findMarker(it)) }
        } finally {
            Timber.uproot(tree)
        }
    }
}
