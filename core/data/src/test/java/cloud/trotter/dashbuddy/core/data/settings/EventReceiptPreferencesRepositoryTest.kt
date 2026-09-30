package cloud.trotter.dashbuddy.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * #1151 — the event-receipt consent over a REAL Preferences DataStore: UNDECIDED by default (the
 * filtered footprint), a decision round-trips through the one [StateFlow] every consumer reads, a
 * corrupt stored name fails closed to UNDECIDED, the value is null until the store was read, and an
 * unreadable store fails closed to null (LL7) instead of terminating the shared flow.
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
    fun `an unreadable store fails closed to null instead of killing the flow`() = runTest {
        val storeScope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        val broken = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IOException("corrupt") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                throw IOException("corrupt")
        }
        val repo = EventReceiptPreferencesRepository(EventReceiptConsentDataSource(broken), storeScope)
        advanceUntilIdle()

        assertNull("unreadable ⇒ null (filtered footprint, no prompt)", repo.consent.value)
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
