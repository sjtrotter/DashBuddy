package cloud.trotter.log.gig.core.data.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.domain.capture.CensusEnvelopeSink
import cloud.trotter.log.gig.domain.census.CensusUploadStats
import cloud.trotter.log.gig.domain.census.HealthLedger
import kotlinx.coroutines.test.StandardTestDispatcher
import cloud.trotter.log.gig.domain.census.CensusDeletion
import cloud.trotter.log.gig.domain.census.CensusLastRun
import cloud.trotter.log.gig.domain.census.CensusRunOutcome
import cloud.trotter.log.gig.domain.census.CensusUploadScheduler
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
import org.mockito.kotlin.spy
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
        override fun enqueueNow(wifiOnly: Boolean, replaceQueued: Boolean) { enqueued += replaceQueued }
        override fun scheduleDaily(runAtMillis: Long, wifiOnly: Boolean) = Unit
        override fun cancelManual() = Unit
        override fun enqueueDeletion() = Unit
    }

    private val credentials: CensusCredentialStore = mock()
    private val spool: CensusSpool = mock()
    private val envelopeSink: CensusEnvelopeSink = mock()
    private val healthStore: HealthLedgerStore = mock()
    private val preferences = spy(DevSettingsRepository(DevSettingsDataSource(MemoryPreferences()), true, Dispatchers.Unconfined, "test"))
    private val scheduler = Scheduler()
    private val lock = CensusUploadLock()
    private val deletion: CensusDataDeletion = mock()

    private suspend fun TestScope.resetter(scope: CoroutineScope = backgroundScope): CensusIdentityResetter {
        whenever(healthStore.load()).thenReturn(HealthLedger())
        val sink = PersistentHealthSink(healthStore, CensusUploadStats(), scope, StandardTestDispatcher(testScheduler))
        return CensusIdentityResetter(credentials, spool, preferences, scheduler, lock, scope, sink, now = { 123L },
            envelopeSink = envelopeSink, deletion = deletion)
    }

    @Test fun `reset clears spool and health before wiping credentials keeps the deadline records RESET and replaces queued work when enabled`() = runTest {
        preferences.setCensusUploadEnabled(true)
        preferences.setNextAllowedAtMillis(99L)
        preferences.setCensusShareCaptures(true)
        preferences.setCensusPolicy(Json.parseToJsonElement("""{"k":10}""").jsonObject)
        preferences.setCensusLastRun(CensusLastRun(5L, CensusRunOutcome.UPLOADED, 12, envelopesPosted = 3))

        resetter().reset()

        inOrder(spool, envelopeSink, preferences, healthStore, credentials) {
            verify(spool).clear()
            verify(envelopeSink).invalidate()
            verify(preferences).setCensusShareCaptures(false)
            verify(healthStore).clear()
            verify(healthStore).save(HealthLedger(generation = 1))
            verify(credentials).wipe()
        }
        assertFalse(preferences.censusShareCaptures.first())
        assertEquals(99L, preferences.nextAllowedAtMillis.first()) // A server-imposed deferral is not identity state.
        assertTrue(preferences.censusPolicy.first().isEmpty())
        assertEquals(CensusLastRun(123L, CensusRunOutcome.RESET), preferences.censusLastRun.first())
        assertEquals(listOf(true), scheduler.enqueued)
        assertTrue(preferences.censusUploadEnabled.first())
    }

    private val enrolled = CensusCredentialStore.Credential("12345678-1234-4123-8123-123456789abc", "s", enrolled = true)

    @Test fun `an enrolled identity is reset only after the server confirms a fresh deletion for it`() = runTest {
        // #1622: no identity with uploads is let go undeleted.
        whenever(credentials.current()).thenReturn(enrolled)
        whenever(deletion.requestHoldingLock()).thenReturn(
            CensusDeletion(1L, CensusDeletion.State.DELETED, localCleared = true, installId = enrolled.installId, rows = 3),
        )
        val reset = resetter()
        assertTrue(reset.reset())
        inOrder(deletion, credentials) {
            verify(deletion).requestHoldingLock()
            verify(credentials).wipe()
        }
    }

    @Test fun `an unconfirmed deletion refuses the reset and keeps the identity`() = runTest {
        preferences.setCensusUploadEnabled(true)
        whenever(credentials.current()).thenReturn(enrolled)
        for (stored in listOf(
            CensusDeletion(1L, CensusDeletion.State.PENDING, localCleared = true, installId = enrolled.installId,
                waiting = CensusDeletion.Waiting.NOT_YET),
            // Confirmed, but the phone's queue is not cleared yet: still not resolved.
            CensusDeletion(1L, CensusDeletion.State.DELETED, localCleared = false, installId = enrolled.installId, rows = 1),
        )) {
            whenever(deletion.requestHoldingLock()).thenReturn(stored)
            assertFalse(resetter().reset())
            assertEquals(CensusRunOutcome.RESET_REFUSED, preferences.censusLastRun.first()?.outcome)
        }
        verify(spool, never()).clear()
        verify(credentials, never()).wipe()
        assertTrue(scheduler.enqueued.isEmpty())
    }

    @Test fun `a pending identity resets without a deletion`() = runTest {
        whenever(credentials.current()).thenReturn(null)
        assertTrue(resetter().reset())
        verify(deletion, never()).requestHoldingLock()
        verify(credentials).wipe()
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

    @Test fun `a failed envelope clear leaves health and identity untouched`() = runTest {
        whenever(envelopeSink.invalidate()).thenAnswer { throw IOException("test_failure") }
        assertFalse(resetter().reset())
        verify(spool).clear()
        verify(healthStore, never()).clear()
        verify(credentials, never()).wipe()
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
