package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EventReceiptConsentDataSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `each decision commits with its receipt and malformed records do not change consent`() = runTest {
        val ds = PreferenceDataStoreFactory.create(
            scope = backgroundScope,
            produceFile = { File(tmp.root, "event-receipt.preferences_pb") },
        )
        val decisionKey = stringPreferencesKey("event_receipt_consent")
        val receiptKey = stringPreferencesKey("event_receipt_consent_receipt_json")
        var edits = 0
        val recording = object : DataStore<Preferences> {
            override val data = ds.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                ds.updateData { before ->
                    edits++
                    if (edits == 1) {
                        assertNull(before[decisionKey])
                        assertNull(before[receiptKey])
                    }
                    transform(before).also { after ->
                        val receipt = Json.decodeFromString<ConsentReceipt>(after[receiptKey]!!)
                        assertEquals(after[decisionKey] == "ALLOWED", receipt.granted)
                    }
                }
        }
        val source = EventReceiptConsentDataSource(recording)
        assertEquals(EventReceiptConsentSnapshot(null, null), source.snapshot.first())
        val allowed = ConsentReceipt(100L, "test-build", 1, true)
        source.setConsent("ALLOWED", allowed)
        assertEquals(1, edits)
        assertEquals(EventReceiptConsentSnapshot("ALLOWED", allowed), source.snapshot.first())
        val declined = allowed.copy(decidedAt = 200L, granted = false)
        source.setConsent("DECLINED", declined)
        assertEquals(2, edits)
        assertEquals(EventReceiptConsentSnapshot("DECLINED", declined), source.snapshot.first())

        ds.edit { it[receiptKey] = "{broken" }
        val snapshot = source.snapshot.first()
        assertNull(snapshot.receipt)
        assertEquals("DECLINED", snapshot.name)
    }
}
