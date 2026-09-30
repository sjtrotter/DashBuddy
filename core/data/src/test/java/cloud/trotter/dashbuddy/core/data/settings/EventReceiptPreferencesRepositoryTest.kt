package cloud.trotter.dashbuddy.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * #1151 — the event-receipt consent over a REAL Preferences DataStore: UNDECIDED by default (the
 * filtered footprint), a decision round-trips through the one [StateFlow] every consumer reads, a
 * corrupt stored name fails closed to UNDECIDED, the value is null until the store was read, and an
 * unreadable store settles on a usable UNDECIDED (NN2) instead of a frozen null, and a failed write
 * never throws (NN3).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventReceiptPreferencesRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newRepo(scope: TestScope, fileName: String): EventReceiptPreferencesRepository {
        val storeScope = CoroutineScope(StandardTestDispatcher(scope.testScheduler) + Job())
        val ds = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, fileName) },
        )
        return EventReceiptPreferencesRepository(EventReceiptConsentDataSource(ds), storeScope)
    }

    @Test
    fun `null before the first read, then UNDECIDED by default`() = runTest {
        val repo = newRepo(this, "a.preferences_pb")
        assertNull("not read yet", repo.consent.value)

        advanceUntilIdle()
        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)
    }

    @Test
    fun `an always-unreadable store settles on a USABLE UNDECIDED, and a later write is observed`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "e.preferences_pb") },
        )
        var readable = false
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (!readable) throw IOException("corrupt")
                emitAll(real.data)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(broken), storeScope)
        runCurrent()
        assertNull("not read yet", repo.consent.value)

        advanceUntilIdle() // every retry exhausted
        assertEquals(
            "exhausted ⇒ UNDECIDED (prompt + switch usable), never a frozen null",
            EventReceiptConsent.UNDECIDED,
            repo.consent.value,
        )

        readable = true
        assertTrue(repo.set(EventReceiptConsent.ALLOWED))
        advanceUntilIdle()
        assertEquals("a successful write is observed", EventReceiptConsent.ALLOWED, repo.consent.value)
    }

    /** A store whose reads work and whose writes all fail. */
    private fun writeFailing(real: DataStore<Preferences>) = object : DataStore<Preferences> {
        override val data: Flow<Preferences> = real.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            throw IOException("disk full")
    }

    @Test
    fun `SS1 - a failed write leaves the previously READ value, never null after a read`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "ss1a.preferences_pb") },
        )
        real.edit { it[stringPreferencesKey("event_receipt_consent")] = "ALLOWED" }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(writeFailing(real)), storeScope)
        advanceUntilIdle()
        assertEquals(EventReceiptConsent.ALLOWED, repo.consent.value)

        assertFalse(repo.set(EventReceiptConsent.DECLINED))
        advanceUntilIdle()
        assertEquals(EventReceiptConsent.ALLOWED, repo.consent.value)
    }

    @Test
    fun `SS1 - two overlapping failing writes leave the flow at the STORED value`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "ss1b.preferences_pb") },
        )
        real.edit { it[stringPreferencesKey("event_receipt_consent")] = "DECLINED" }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(writeFailing(real)), storeScope)
        advanceUntilIdle()

        // ALLOWED then DECLINED, overlapping, both failing — the pre-SS1 value-compared rollback
        // ended at ALLOWED here while the store said DECLINED.
        val first = async { repo.set(EventReceiptConsent.ALLOWED) }
        val second = async { repo.set(EventReceiptConsent.DECLINED) }
        assertFalse(first.await())
        assertFalse(second.await())
        advanceUntilIdle()

        assertEquals(EventReceiptConsent.DECLINED, repo.consent.value)
    }

    @Test
    fun `SS1 - a successful write is observed through the collector only`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "ss1c.preferences_pb") },
        )
        // Reads never reflect writes: whatever set() did, only the collector may move the value.
        val frozenReads = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(frozenReads), storeScope)
        advanceUntilIdle()
        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)

        assertTrue(repo.set(EventReceiptConsent.ALLOWED))
        advanceUntilIdle()
        assertEquals("set() never publishes on its own", EventReceiptConsent.UNDECIDED, repo.consent.value)
    }

    @Test
    fun `UU1 - a write that lands while the collector is in its exhausted catch is read back`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "uu1.preferences_pb") },
        )
        real.edit { it[stringPreferencesKey("event_receipt_consent")] = "DECLINED" }
        val lastFailureGate = CompletableDeferred<Unit>()
        val failing = EventReceiptPreferencesRepository.MAX_RETRIES + 1
        var collections = 0
        val flaky = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                collections++
                when {
                    collections < failing -> throw IOException("unreadable #$collections")
                    collections == failing -> {
                        // The LAST failure waits here, so the write completes between it and the
                        // exhausted catch's emit of the stale value.
                        lastFailureGate.await()
                        throw IOException("unreadable — exhausting")
                    }
                    else -> emitAll(real.data)
                }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(flaky), storeScope)
        advanceUntilIdle() // parked on the last failure's gate
        assertEquals(failing, collections)

        assertTrue(repo.set(EventReceiptConsent.ALLOWED)) // lands while the collector is mid-exhaustion
        lastFailureGate.complete(Unit) // … the catch now emits the stale value and the loop re-reads
        advanceUntilIdle()

        assertEquals("the written value is read back", EventReceiptConsent.ALLOWED, repo.consent.value)
    }

    @Test
    fun `UU2 - two successful writes after exhaustion leave exactly ONE subscription`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "uu2.preferences_pb") },
        )
        var readsBroken = true
        var active = 0
        var maxActive = 0
        val counting = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                if (readsBroken) throw IOException("unreadable")
                active++
                maxActive = maxOf(maxActive, active)
                try {
                    emitAll(real.data)
                } finally {
                    active--
                }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(counting), storeScope)
        advanceUntilIdle() // exhausted → UNDECIDED, waiting for a poke
        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)

        readsBroken = false
        val first = async { repo.set(EventReceiptConsent.ALLOWED) }
        val second = async { repo.set(EventReceiptConsent.DECLINED) }
        assertTrue(first.await())
        assertTrue(second.await())
        advanceUntilIdle()

        assertEquals(1, active)
        assertEquals("never two collectors", 1, maxActive)
        assertEquals(EventReceiptConsent.DECLINED, repo.consent.value)
    }

    @Test
    fun `a failed write never throws, reports false, and leaves the value unchanged`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "f.preferences_pb") },
        )
        val readOnly = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = real.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(readOnly), storeScope)
        advanceUntilIdle()
        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)

        assertFalse(repo.set(EventReceiptConsent.ALLOWED))
        advanceUntilIdle()
        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)
    }

    @Test
    fun `a transient read failure recovers to the stored value and later writes are observed`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "d.preferences_pb") },
        )
        real.edit { it[stringPreferencesKey("event_receipt_consent")] = "DECLINED" }
        var collections = 0
        val throwOnce = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                collections++
                if (collections == 1) throw IOException("transient")
                emitAll(real.data)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(throwOnce), storeScope)

        runCurrent()
        assertNull("while retrying: fail-closed null", repo.consent.value)

        advanceUntilIdle()
        assertEquals("recovered to the stored value", EventReceiptConsent.DECLINED, repo.consent.value)

        repo.set(EventReceiptConsent.ALLOWED)
        advanceUntilIdle()
        assertEquals("a later write is read back", EventReceiptConsent.ALLOWED, repo.consent.value)
    }

    @Test
    fun `set round-trips through the consent flow`() = runTest {
        val repo = newRepo(this, "b.preferences_pb")
        advanceUntilIdle()

        repo.set(EventReceiptConsent.ALLOWED)
        advanceUntilIdle()
        assertEquals(EventReceiptConsent.ALLOWED, repo.consent.value)

        repo.set(EventReceiptConsent.DECLINED)
        advanceUntilIdle()
        assertEquals(EventReceiptConsent.DECLINED, repo.consent.value)
    }

    @Test
    fun `an unknown stored name fails closed to UNDECIDED`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val ds = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "c.preferences_pb") },
        )
        ds.edit { it[stringPreferencesKey("event_receipt_consent")] = "WIDE_OPEN" }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(ds), storeScope)
        advanceUntilIdle()

        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)
    }

    @Test
    fun `of maps the on-off rule`() {
        assertEquals(EventReceiptConsent.ALLOWED, EventReceiptConsent.of(true))
        assertEquals(EventReceiptConsent.DECLINED, EventReceiptConsent.of(false))
    }

    @Test
    fun `decode maps only exact names`() {
        assertEquals(EventReceiptConsent.UNDECIDED, EventReceiptPreferencesRepository.decode(null))
        assertEquals(EventReceiptConsent.UNDECIDED, EventReceiptPreferencesRepository.decode("allowed"))
        assertEquals(EventReceiptConsent.ALLOWED, EventReceiptPreferencesRepository.decode("ALLOWED"))
        assertEquals(EventReceiptConsent.DECLINED, EventReceiptPreferencesRepository.decode("DECLINED"))
    }
}
