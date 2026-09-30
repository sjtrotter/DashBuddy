package cloud.trotter.dashbuddy.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.dashbuddy.domain.settings.EventReceiptConsent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #1151 — the event-receipt consent over a REAL Preferences DataStore: UNDECIDED by default (the
 * filtered footprint), a decision round-trips through the one [StateFlow] every consumer reads, a
 * corrupt stored name fails closed to UNDECIDED, and [loaded] only turns true once the store was read.
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
    fun `defaults to UNDECIDED and is not loaded before the first read`() = runTest {
        val repo = newRepo(this, "a.preferences_pb")
        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)
        assertFalse(repo.loaded.value)

        advanceUntilIdle()
        assertEquals(EventReceiptConsent.UNDECIDED, repo.consent.value)
        assertTrue(repo.loaded.value)
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
        assertTrue(repo.loaded.value)
    }

    @Test
    fun `decode maps only exact names`() {
        assertEquals(EventReceiptConsent.UNDECIDED, EventReceiptPreferencesRepository.decode(null))
        assertEquals(EventReceiptConsent.UNDECIDED, EventReceiptPreferencesRepository.decode("allowed"))
        assertEquals(EventReceiptConsent.ALLOWED, EventReceiptPreferencesRepository.decode("ALLOWED"))
        assertEquals(EventReceiptConsent.DECLINED, EventReceiptPreferencesRepository.decode("DECLINED"))
    }
}
