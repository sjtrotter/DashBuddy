package cloud.trotter.log.gig.core.data.capability

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import cloud.trotter.log.gig.core.datastore.capability.GrantSnapshot
import cloud.trotter.log.gig.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.log.gig.core.datastore.settings.EventReceiptConsentDataSource
import cloud.trotter.log.gig.domain.capability.ConsentReceipt
import cloud.trotter.log.gig.domain.capability.StoredConsentReceipts
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #170 (Sol F1) — the export's receipt read goes to both stores, not to the screens' caches. */
class StoreConsentReceiptReaderTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `reads the stored Screen events and capability receipts`() = runTest {
        fun store(name: String) = PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { tmp.newFile(name) })
        val capabilities = RuleCapabilityDataSource(store("caps.preferences_pb"))
        val events = EventReceiptConsentDataSource(store("events.preferences_pb"), store("app.preferences_pb"))
        val reader = StoreConsentReceiptReader(capabilities, events)
        assertEquals(StoredConsentReceipts(null, emptyMap()), reader.read())

        val granted = ConsentReceipt(1_791_244_800_000L, "0.230.1", 16, true)
        val screenEvents = granted.copy(granted = false)
        capabilities.update { _, _, _ -> GrantSnapshot(setOf("k"), emptySet(), mapOf("k" to granted)) }
        events.setConsent("DECLINED", screenEvents)

        assertEquals(StoredConsentReceipts(screenEvents, mapOf("k" to granted)), reader.read())
    }
}
