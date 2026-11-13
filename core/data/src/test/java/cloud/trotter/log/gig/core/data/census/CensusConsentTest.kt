package cloud.trotter.log.gig.core.data.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.log.gig.core.data.settings.DevSettingsRepository
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.PrivacyDisclosure
import cloud.trotter.log.gig.domain.census.CensusChoices
import cloud.trotter.log.gig.domain.census.CensusConsent
import cloud.trotter.log.gig.domain.census.CensusConsentReceipt
import cloud.trotter.log.gig.domain.state.Platform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1621: the census opt-in's consent, bound to the census endpoint, and its choices. */
class CensusConsentTest {
    private class MemoryPreferences : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val store = MemoryPreferences()
    private val source = DevSettingsDataSource(store)
    private fun TestScope.repo(debug: Boolean = true) =
        DevSettingsRepository(source, debug, StandardTestDispatcher(testScheduler), "1.2.3")

    private suspend fun storedReceipt(): CensusConsentReceipt? = source.censusConsent.first().receipt

    @Test fun `consent and choices default off, every platform, Wi-Fi only`() = runTest {
        val repo = repo()
        assertEquals(CensusConsent.OFF, repo.censusConsent.first())
        assertFalse(repo.enabled.first())
        val choices = repo.censusChoices.first()
        assertNull(choices.platforms)
        assertTrue(choices.wifiOnly)
        assertEquals(Platform.boundPlatforms.toSet(), choices.platformsAmong(Platform.boundPlatforms))
    }

    @Test fun `accepting records a granted receipt bound to the endpoint, with the choices, in one edit`() = runTest {
        val repo = repo()
        repo.setCensusUploadEnabled(true, CensusChoices(setOf(Platform.DoorDash), wifiOnly = false))

        assertEquals(CensusConsent.ON, repo.censusConsent.first())
        assertTrue(repo.enabled.first())
        val receipt = requireNotNull(storedReceipt())
        assertEquals(repo.baseUrl.first(), receipt.endpoint)
        assertTrue(receipt.receipt.granted)
        assertEquals("1.2.3", receipt.receipt.appVersion)
        assertEquals(PrivacyDisclosure.REVISION, receipt.receipt.disclosureRevision)
        assertEquals(CensusChoices(setOf(Platform.DoorDash), wifiOnly = false), repo.censusChoices.first())
    }

    @Test fun `a receipt for another endpoint drops the consent until accepted again`() = runTest {
        val repo = repo()
        repo.setCensusUploadEnabled(true)
        source.setCensusUploadEnabled(
            true,
            CensusConsentReceipt(ConsentReceipt(1L, "1.0.0", 19, granted = true), "https://census.old.example"),
        )

        assertEquals(CensusConsent.ENDPOINT_CHANGED, repo.censusConsent.first())
        assertFalse(repo.enabled.first())

        repo.setCensusUploadEnabled(true)
        assertEquals(CensusConsent.ON, repo.censusConsent.first())
    }

    @Test fun `a switch with no receipt, or an unreadable one, is off`() = runTest {
        val repo = repo()
        store.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey("census_upload_enabled")] = true }
        assertEquals(CensusConsent.OFF, repo.censusConsent.first())
        store.edit { it[stringPreferencesKey("census_consent_receipt_json")] = "{not json" }
        assertEquals(CensusConsent.OFF, repo.censusConsent.first())
        assertFalse(repo.enabled.first())
    }

    @Test fun `a decline is recorded and durable - a revoke, the switch and a new repository keep it off`() = runTest {
        val repo = repo()
        repo.setCensusUploadEnabled(true)
        repo.setCensusShareCaptures(true)
        repo.declineCensus()

        assertEquals(CensusConsent.OFF, repo.censusConsent.first())
        assertFalse(repo.censusShareCaptures.first())
        val receipt = requireNotNull(storedReceipt())
        assertFalse(receipt.receipt.granted)
        assertEquals(repo.baseUrl.first(), receipt.endpoint)

        store.edit { it[androidx.datastore.preferences.core.booleanPreferencesKey("census_upload_enabled")] = true }
        assertEquals(CensusConsent.OFF, repo().censusConsent.first())
        assertFalse(repo().enabled.first())
    }

    @Test fun `a server revoke turns the switch off and keeps the dasher's receipt`() = runTest {
        val repo = repo()
        repo.setCensusUploadEnabled(true)
        val accepted = storedReceipt()
        repo.setCensusUploadEnabled(false)
        assertEquals(CensusConsent.OFF, repo.censusConsent.first())
        assertEquals(accepted, storedReceipt())
    }

    @Test fun `every build stores the choice and only the debug build sends`() = runTest {
        val release = repo(debug = false)
        release.setCensusUploadEnabled(true)
        assertEquals(CensusConsent.ON, release.censusConsent.first())
        assertFalse(release.enabled.first())
        assertFalse(release.censusAvailable)
        assertTrue(repo(debug = true).enabled.first())
    }

    @Test fun `choices save on their own and unknown platform wires are dropped`() = runTest {
        val repo = repo()
        repo.setCensusWifiOnly(false)
        repo.setCensusPlatform(Platform.DoorDash, covered = false, readable = Platform.boundPlatforms)
        assertEquals(CensusChoices(setOf(Platform.Uber), wifiOnly = false), repo.censusChoices.first())
        store.edit {
            it[androidx.datastore.preferences.core.stringSetPreferencesKey("census_platforms")] = setOf("doordash", "retired")
        }
        assertEquals(setOf(Platform.DoorDash), repo.censusChoices.first().platforms)
        assertEquals(CensusConsent.OFF, repo.censusConsent.first())
    }
}
