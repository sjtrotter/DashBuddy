package cloud.trotter.dashbuddy.core.data.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.dashbuddy.domain.capture.CensusRecord
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.census.EnvelopeProjection
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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
            assertFalse(h.sink.pair("id", fingerprint))
        }
        h.sink.hold("deep", Platform.DoorDash, envelope("[".repeat(66) + "0" + "]".repeat(66)))
        assertFalse(h.sink.pair("deep", fingerprint))
        h.sink.hold("invalid", Platform.DoorDash, "{}")
        assertFalse(h.sink.pair("invalid", fingerprint))
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
}
