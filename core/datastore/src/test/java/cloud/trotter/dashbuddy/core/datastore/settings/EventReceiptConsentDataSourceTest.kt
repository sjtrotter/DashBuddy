package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class EventReceiptConsentDataSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val decisionKey = stringPreferencesKey("event_receipt_consent")
    private val receiptKey = stringPreferencesKey("event_receipt_consent_receipt_json")
    private val economyKey = stringPreferencesKey("vehicle")
    private val receipt = ConsentReceipt(100L, "test-build", 1, true)

    private fun TestScope.store(name: String) = PreferenceDataStoreFactory.create(
        scope = backgroundScope,
        produceFile = { File(tmp.root, "$name.preferences_pb") },
    )

    private suspend fun seedLegacy(ds: DataStore<Preferences>) {
        ds.edit {
            it[decisionKey] = "ALLOWED"
            it[receiptKey] = Json.encodeToString(receipt)
            it[economyKey] = "my vehicle"
        }
    }

    @Test
    fun `first read moves both keys once and restored economy preferences cannot restore consent`() = runTest {
        val old = store("app_prefs")
        val new = store("consent_event_receipt")
        seedLegacy(old)
        var writes = 0
        val recording = object : DataStore<Preferences> {
            override val data = new.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                writes++
                // Legacy keys must still exist until this write has succeeded.
                assertEquals("ALLOWED", old.data.first()[decisionKey])
                return new.updateData(transform)
            }
        }
        val source = EventReceiptConsentDataSource(recording, old)
        val expected = EventReceiptConsentSnapshot("ALLOWED", receipt)
        assertEquals(expected, source.snapshot.first())
        assertNull(old.data.first()[decisionKey])
        assertNull(old.data.first()[receiptKey])
        assertEquals("my vehicle", old.data.first()[economyKey])
        assertEquals(expected, source.snapshot.first())
        assertEquals(expected, EventReceiptConsentDataSource(recording, old).snapshot.first())
        assertEquals(1, writes)
        // The backed-up app preferences survive, while the excluded consent store starts empty.
        assertEquals(
            EventReceiptConsentSnapshot(null, null),
            EventReceiptConsentDataSource(store("new_phone"), old).snapshot.first(),
        )
    }

    @Test
    fun `non-empty destination ignores legacy keys and removes them from backed-up preferences`() = runTest {
        val old = store("app_prefs")
        val new = store("consent_event_receipt")
        seedLegacy(old)
        val declined = receipt.copy(granted = false)
        new.edit {
            it[decisionKey] = "DECLINED"
            it[receiptKey] = Json.encodeToString(declined)
        }
        assertEquals(
            EventReceiptConsentSnapshot("DECLINED", declined),
            EventReceiptConsentDataSource(new, old).snapshot.first(),
        )
        assertNull(old.data.first()[decisionKey])
        assertNull(old.data.first()[receiptKey])
        assertEquals("my vehicle", old.data.first()[economyKey])
    }

    @Test
    fun `failed destination write preserves legacy keys for retry`() = runTest {
        val old = store("app_prefs")
        val new = store("consent_event_receipt")
        seedLegacy(old)
        var failWrites = true
        val failing = object : DataStore<Preferences> {
            override val data = new.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (failWrites) throw IOException("disk full")
                return new.updateData(transform)
            }
        }
        val source = EventReceiptConsentDataSource(failing, old)
        try {
            source.snapshot.first()
            fail("Migration must not expose consent before it is safely moved")
        } catch (_: IOException) {
            assertEquals("ALLOWED", old.data.first()[decisionKey])
            assertEquals(Json.encodeToString(receipt), old.data.first()[receiptKey])
        }
        failWrites = false
        assertEquals(EventReceiptConsentSnapshot("ALLOWED", receipt), source.snapshot.first())
        assertNull(old.data.first()[decisionKey])
        assertNull(old.data.first()[receiptKey])
    }

    @Test
    fun `failed legacy cleanup retries without overwriting the destination`() = runTest {
        val old = store("app_prefs")
        val new = store("consent_event_receipt")
        seedLegacy(old)
        var failCleanup = true
        val failing = object : DataStore<Preferences> {
            override val data = old.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                assertEquals("ALLOWED", new.data.first()[decisionKey])
                if (failCleanup) throw IOException("cleanup failed")
                return old.updateData(transform)
            }
        }
        val source = EventReceiptConsentDataSource(new, failing)
        try {
            source.snapshot.first()
            fail("Cleanup failure should be retried")
        } catch (_: IOException) {
            assertEquals("ALLOWED", new.data.first()[decisionKey])
            assertEquals("ALLOWED", old.data.first()[decisionKey])
        }
        old.edit { it[decisionKey] = "DECLINED" }
        failCleanup = false
        assertEquals(EventReceiptConsentSnapshot("ALLOWED", receipt), source.snapshot.first())
        assertNull(old.data.first()[decisionKey])
        assertNull(old.data.first()[receiptKey])
    }

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
        val source = EventReceiptConsentDataSource(recording, store("app_prefs"))
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
