package cloud.trotter.dashbuddy.core.data.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.dashbuddy.domain.census.CensusLastRun
import cloud.trotter.dashbuddy.domain.census.CensusRunOutcome
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class CensusIdentityResetterTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class Scheduler : CensusUploadScheduler {
        var enqueued = 0
        override fun enqueueNow() { enqueued++ }
        override fun enqueueSoon() = Unit
        override fun deferUntil(epochMillis: Long) = Unit
    }

    private val credentials: CensusCredentialStore = mock()
    private val spool: CensusSpool = mock()
    private val preferences = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), true, Dispatchers.Unconfined)
    private val scheduler = Scheduler()
    private val lock = CensusUploadLock()
    private val resetter = CensusIdentityResetter(credentials, spool, preferences, scheduler, lock, now = { 123L })

    @Test fun `reset wipes credentials clears the spool and run state records RESET and enqueues when enabled`() = runTest {
        preferences.setCensusUploadEnabled(true)
        preferences.setNextAllowedAtMillis(99L)
        preferences.setCensusPolicy(Json.parseToJsonElement("""{"k":10}""").jsonObject)
        preferences.setCensusLastRun(CensusLastRun(5L, CensusRunOutcome.UPLOADED, 12))

        resetter.reset()

        verify(credentials).wipe()
        verify(spool).clear()
        assertEquals(0L, preferences.nextAllowedAtMillis.first())
        assertTrue(preferences.censusPolicy.first().isEmpty())
        assertEquals(CensusLastRun(123L, CensusRunOutcome.RESET), preferences.censusLastRun.first())
        assertEquals(1, scheduler.enqueued)
        assertTrue(preferences.censusUploadEnabled.first())
    }

    @Test fun `reset does not enqueue when consent is off`() = runTest {
        resetter.reset()

        verify(credentials).wipe()
        verify(spool).clear()
        assertEquals(0, scheduler.enqueued)
        assertFalse(preferences.censusUploadEnabled.first())
        assertEquals(CensusLastRun(123L, CensusRunOutcome.RESET), preferences.censusLastRun.first())
    }

    @Test fun `reset waits for a running upload`() = runTest {
        lock.mutex.lock()
        try {
            backgroundScope.launch { resetter.reset() }
            runCurrent()
            verify(credentials, never()).wipe()
        } finally {
            lock.mutex.unlock()
        }
        runCurrent()
        verify(credentials).wipe()
        verify(spool).clear()
    }
}
