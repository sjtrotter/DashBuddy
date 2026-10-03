package cloud.trotter.dashbuddy.core.data.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.dashbuddy.core.data.settings.DevSettingsRepository
import cloud.trotter.dashbuddy.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.census.HealthLedger
import kotlinx.coroutines.test.StandardTestDispatcher
import cloud.trotter.dashbuddy.domain.census.CensusLastRun
import cloud.trotter.dashbuddy.domain.census.CensusRunOutcome
import cloud.trotter.dashbuddy.domain.census.CensusUploadScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class CensusIdentityResetterTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class Scheduler : CensusUploadScheduler {
        val enqueued = mutableListOf<Boolean>()
        override fun enqueueNow(replaceQueued: Boolean) { enqueued += replaceQueued }
        override fun enqueueSoon() = Unit
        override fun deferUntil(epochMillis: Long) = Unit
    }

    private val credentials: CensusCredentialStore = mock()
    private val spool: CensusSpool = mock()
    private val healthStore: HealthLedgerStore = mock()
    private val preferences = DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), true, Dispatchers.Unconfined)
    private val scheduler = Scheduler()
    private val lock = CensusUploadLock()

    private suspend fun TestScope.resetter(scope: CoroutineScope = backgroundScope): CensusIdentityResetter {
        whenever(healthStore.load()).thenReturn(HealthLedger())
        val sink = PersistentHealthSink(healthStore, CensusUploadStats(), scope, StandardTestDispatcher(testScheduler))
        return CensusIdentityResetter(credentials, spool, preferences, scheduler, lock, scope, sink, now = { 123L })
    }

    @Test fun `reset clears spool and health before wiping credentials keeps the deadline records RESET and replaces queued work when enabled`() = runTest {
        preferences.setCensusUploadEnabled(true)
        preferences.setNextAllowedAtMillis(99L)
        preferences.setCensusPolicy(Json.parseToJsonElement("""{"k":10}""").jsonObject)
        preferences.setCensusLastRun(CensusLastRun(5L, CensusRunOutcome.UPLOADED, 12))

        resetter().reset()

        inOrder(spool, healthStore, credentials) {
            verify(spool).clear()
            verify(healthStore).clear()
            verify(healthStore).save(HealthLedger(generation = 1))
            verify(credentials).wipe()
        }
        assertEquals(99L, preferences.nextAllowedAtMillis.first()) // A server-imposed deferral is not identity state.
        assertTrue(preferences.censusPolicy.first().isEmpty())
        assertEquals(CensusLastRun(123L, CensusRunOutcome.RESET), preferences.censusLastRun.first())
        assertEquals(listOf(true), scheduler.enqueued)
        assertTrue(preferences.censusUploadEnabled.first())
    }

    @Test fun `reset does not enqueue when consent is off`() = runTest {
        resetter().reset()

        verify(credentials).wipe()
        verify(spool).clear()
        assertTrue(scheduler.enqueued.isEmpty())
        assertFalse(preferences.censusUploadEnabled.first())
        assertEquals(CensusLastRun(123L, CensusRunOutcome.RESET), preferences.censusLastRun.first())
    }

    @Test fun `reset waits for a running upload`() = runTest {
        lock.mutex.lock()
        try {
            backgroundScope.launch { resetter().reset() }
            runCurrent()
            verify(credentials, never()).wipe()
        } finally {
            lock.mutex.unlock()
        }
        runCurrent()
        verify(credentials).wipe()
        verify(spool).clear()
    }

    @Test fun `a failed spool clear leaves the identity in place and is recorded not thrown`() = runTest {
        preferences.setCensusUploadEnabled(true)
        whenever(spool.clear()).thenAnswer { throw IOException("Census spool clear failed") }

        assertFalse(resetter().reset())

        verify(credentials, never()).wipe()
        verify(healthStore, never()).clear()
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(CensusLastRun(123L, CensusRunOutcome.FAILURE), preferences.censusLastRun.first())
    }

    @Test fun `a failed health clear leaves the identity in place and is recorded not thrown`() = runTest {
        preferences.setCensusUploadEnabled(true)
        whenever(healthStore.clear()).thenAnswer { throw IOException("Census health clear failed") }

        assertFalse(resetter().reset())

        verify(spool).clear()
        verify(credentials, never()).wipe()
        assertTrue(scheduler.enqueued.isEmpty())
        assertEquals(CensusLastRun(123L, CensusRunOutcome.FAILURE), preferences.censusLastRun.first())
    }

    @Test fun `a failure is recorded inside the lock so a later successful reset is not overwritten`() = runTest {
        var calls = 0
        whenever(spool.clear()).thenAnswer { if (++calls == 1) throw IOException("first clear fails") else Unit }
        val first = resetter()
        assertFalse(first.reset())
        assertEquals(CensusLastRun(123L, CensusRunOutcome.FAILURE), preferences.censusLastRun.first())
        assertTrue(first.reset())
        assertEquals(CensusLastRun(123L, CensusRunOutcome.RESET), preferences.censusLastRun.first())
    }

    @Test fun `resetAsync runs on the application scope not the caller's`() = runTest {
        preferences.setCensusUploadEnabled(true)
        val job = resetter(scope = backgroundScope).resetAsync()
        // The caller (a ViewModel leaving the screen) holds no handle the reset depends on; it completes on its own scope.
        job.join()
        verify(spool).clear()
        verify(credentials).wipe()
        assertEquals(listOf(true), scheduler.enqueued)
    }
}
