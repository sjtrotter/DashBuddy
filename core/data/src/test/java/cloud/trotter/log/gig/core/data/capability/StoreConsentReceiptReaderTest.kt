package cloud.trotter.log.gig.core.data.capability

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.gig.core.datastore.capability.GrantSnapshot
import cloud.trotter.log.gig.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.log.gig.core.datastore.census.ContributorDataSource
import cloud.trotter.log.gig.core.datastore.settings.DevSettingsDataSource
import cloud.trotter.log.gig.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.log.gig.domain.census.CensusConsentReceipt
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.StoredConsentReceipts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #170 (Sol F1) — the export's receipt read goes to the stores, not to the screens' caches. */
class StoreConsentReceiptReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `reads the stored Screen events and capability receipts`() = runTest {
        fun store(name: String) = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { tmp.newFile(name) })
        val capabilities = RuleCapabilityDataSource(store("caps.preferences_pb"))
        val events = EventReceiptConsentDataSource(store("events.preferences_pb"), store("app.preferences_pb"))
        val devSettings = DevSettingsDataSource(store("dev.preferences_pb"))
        val contributor = ContributorDataSource(store("contributor.preferences_pb"))
        val reader = StoreConsentReceiptReader(capabilities, events, devSettings, contributor)
        assertEquals(StoredConsentReceipts(null, emptyMap()), reader.read())

        val granted = ConsentReceipt(1_791_244_800_000L, "0.230.1", 16, true)
        val screenEvents = granted.copy(granted = false)
        capabilities.update { _, _, _ -> GrantSnapshot(setOf("k"), emptySet(), mapOf("k" to granted)) }
        events.setConsent("DECLINED", screenEvents)
        val census = granted.copy(decidedAt = 2L)
        devSettings.setCensusUploadEnabled(true, CensusConsentReceipt(census, "https://census.example"))
        val reviews = granted.copy(decidedAt = 3L, granted = false)
        contributor.update { it.copy(receipt = reviews) }

        assertEquals(StoredConsentReceipts(screenEvents, mapOf("k" to granted), census, reviews), reader.read())
    }
}
