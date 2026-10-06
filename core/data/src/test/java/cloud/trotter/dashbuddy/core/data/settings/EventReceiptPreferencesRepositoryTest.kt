package cloud.trotter.dashbuddy.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.capability.PrivacyDisclosure
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
        return EventReceiptPreferencesRepository(EventReceiptConsentDataSource(ds), storeScope, "test-build")
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
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(broken), storeScope, "test-build")
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
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(writeFailing(real)), storeScope, "test-build")
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
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(writeFailing(real)), storeScope, "test-build")
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
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(frozenReads), storeScope, "test-build")
        advanceUntilIdle()
        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)

        assertTrue(repo.set(EventReceiptConsent.ALLOWED))
        advanceUntilIdle()
        assertEquals("set() never publishes on its own", EventReceiptConsent.UNDECIDED, repo.consent.value)
        assertNull("receipt also comes only from the collector", repo.receipt.value)
    }

    @Test
    fun `UU1 - a poke sent before the exhausted catch completes is buffered and the write is read back`() = runTest {
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
                        // The LAST failure waits here: the write (and its poke) complete BEFORE the
                        // exhausted catch runs, so the conflated channel buffers the poke and the
                        // loop consumes it at receive() — the write is read back.
                        lastFailureGate.await()
                        throw IOException("unreadable — exhausting")
                    }
                    else -> emitAll(real.data)
                }
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(flaky), storeScope, "test-build")
        advanceUntilIdle() // parked on the last failure's gate
        assertEquals(failing, collections)

        assertTrue(repo.set(EventReceiptConsent.ALLOWED)) // write + poke, before the catch runs
        lastFailureGate.complete(Unit) // … the catch emits the stale value; receive() takes the poke
        advanceUntilIdle()

        assertEquals("the written value is read back", EventReceiptConsent.ALLOWED, repo.consent.value)
    }

    @Test
    fun `UU1 - write persisted first, poke sent only after the exhausted catch ran - still read back`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "uu1b.preferences_pb") },
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
                        lastFailureGate.await()
                        throw IOException("unreadable — exhausting")
                    }
                    else -> emitAll(real.data)
                }
            }
            // The real-device ordering: the write PERSISTS, then the final read failure is released
            // and the collector runs its exhausted catch (emitting the stale value) and parks at
            // receive() — and only THEN does set() return and send its poke.
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val written = real.updateData(transform)
                lastFailureGate.complete(Unit)
                delay(1) // lets the collector finish the catch and reach receive() before the poke
                return written
            }
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(flaky), storeScope, "test-build")
        advanceUntilIdle()
        assertEquals(failing, collections)

        assertTrue(repo.set(EventReceiptConsent.ALLOWED))
        advanceUntilIdle()

        assertEquals("the written value is read back", EventReceiptConsent.ALLOWED, repo.consent.value)
    }

    @Test
    fun `UU2 - ONE collector for the repository's lifetime, by identity`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val real = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "uu2.preferences_pb") },
        )
        var readsBroken = true
        // The collecting coroutine's Job on EVERY subscription, failing ones included. A design that
        // re-launches a reader (the old `if (!reader.isActive) startReader()`) records a second Job.
        val collectorJobs = mutableListOf<Job?>()
        val recording = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow {
                collectorJobs += currentCoroutineContext()[Job]
                if (readsBroken) throw IOException("unreadable")
                emitAll(real.data)
            }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                real.updateData(transform)
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(recording), storeScope, "test-build")
        advanceUntilIdle() // exhausted → UNDECIDED, waiting for a poke
        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)
        val lifetimeCollector = collectorJobs.first()

        readsBroken = false
        val first = async { repo.set(EventReceiptConsent.ALLOWED) }
        val second = async { repo.set(EventReceiptConsent.DECLINED) }
        assertTrue(first.await())
        assertTrue(second.await())
        advanceUntilIdle()

        assertEquals(EventReceiptConsent.DECLINED, repo.consent.value)
        assertTrue("subscribed again after the writes", collectorJobs.size > EventReceiptPreferencesRepository.MAX_RETRIES + 1)
        assertTrue(
            "every subscription — before exhaustion and after both writes — is the SAME collector",
            collectorJobs.all { it === lifetimeCollector },
        )
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
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(readOnly), storeScope, "test-build")
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
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(throwOnce), storeScope, "test-build")

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
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(ds), storeScope, "test-build")
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

    @Test
    fun `allow and decline stamp the latest receipt with the injected version`() = runTest {
        val repo = newRepo(this, "receipts.preferences_pb")
        advanceUntilIdle()
        assertNull(repo.receipt.value)
        val before = System.currentTimeMillis()
        assertTrue(repo.set(EventReceiptConsent.ALLOWED))
        advanceUntilIdle()
        val allowed = requireNotNull(repo.receipt.value)
        assertTrue(allowed.granted)
        assertEquals("test-build", allowed.appVersion)
        assertEquals(PrivacyDisclosure.REVISION, allowed.disclosureRevision)
        assertTrue(allowed.decidedAt in before..System.currentTimeMillis())

        assertTrue(repo.set(EventReceiptConsent.DECLINED))
        advanceUntilIdle()
        val declined = requireNotNull(repo.receipt.value)
        assertFalse(declined.granted)
        assertEquals("test-build", declined.appVersion)
        assertEquals(PrivacyDisclosure.REVISION, declined.disclosureRevision)
        assertTrue(declined.decidedAt >= allowed.decidedAt)
    }

    @Test
    fun `failed decision write leaves its previously persisted receipt unchanged`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val ds = PreferenceDataStoreFactory.create(
            scope = storeScope,
            produceFile = { File(tmp.root, "failed-receipt.preferences_pb") },
        )
        var failWrites = false
        val failing = object : DataStore<Preferences> {
            override val data = ds.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrites) throw IOException("disk full")
                return ds.updateData(transform)
            }
        }
        val source = EventReceiptConsentDataSource(failing)
        val repo = EventReceiptPreferencesRepository(source, storeScope, "test-build")
        assertTrue(repo.set(EventReceiptConsent.ALLOWED))
        advanceUntilIdle()
        val record = requireNotNull(repo.receipt.value)
        failWrites = true
        assertFalse(repo.set(EventReceiptConsent.DECLINED))
        advanceUntilIdle()
        assertEquals(EventReceiptConsent.ALLOWED, repo.consent.value)
        assertEquals(record, repo.receipt.value)
        assertEquals(record, source.receipt.first())
    }
}
