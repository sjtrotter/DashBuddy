package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import timber.log.Timber
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
    fun `first read purges legacy keys without copying and repeated reads skip the edit`() = runTest {
        val old = store("app_prefs")
        val new = store("consent_event_receipt")
        seedLegacy(old)
        var edits = 0
        val recording = object : DataStore<Preferences> {
            override val data = old.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                edits++
                return old.updateData(transform)
            }
        }
        val source = EventReceiptConsentDataSource(new, recording)
        assertEquals(EventReceiptConsentSnapshot(null, null), source.snapshot.first())
        assertNull(old.data.first()[decisionKey])
        assertNull(old.data.first()[receiptKey])
        assertEquals("my vehicle", old.data.first()[economyKey])
        assertEquals(EventReceiptConsentSnapshot(null, null), source.snapshot.first())
        assertEquals(1, edits)
        assertEquals(emptyMap<Preferences.Key<*>, Any>(), new.data.first().asMap())

        source.setConsent("ALLOWED", receipt)
        assertEquals(EventReceiptConsentSnapshot("ALLOWED", receipt), source.snapshot.first())
        assertEquals(1, edits)
        assertNull(old.data.first()[decisionKey])
        assertNull(old.data.first()[receiptKey])
        // A restored app_prefs from BEFORE this upgrade must not restore consent either.
        seedLegacy(old)
        assertEquals(
            EventReceiptConsentSnapshot(null, null),
            EventReceiptConsentDataSource(store("new_phone"), old).snapshot.first(),
        )
        assertNull(old.data.first()[decisionKey])
        assertNull(old.data.first()[receiptKey])
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
    fun `failed purge is ignored and retried without copying or blocking a new decision`() = runTest {
        val old = store("app_prefs")
        val new = store("consent_event_receipt")
        seedLegacy(old)
        var failCleanup = true
        var attempts = 0
        val failing = object : DataStore<Preferences> {
            override val data = old.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                attempts++
                if (failCleanup) throw IOException("cleanup failed")
                return old.updateData(transform)
            }
        }
        val source = EventReceiptConsentDataSource(new, failing)
        repeat(2) {
            assertEquals(EventReceiptConsentSnapshot(null, null), source.snapshot.first())
        }
        assertEquals(2, attempts)
        assertEquals("ALLOWED", old.data.first()[decisionKey])
        assertNull(new.data.first()[decisionKey])
        source.setConsent("ALLOWED", receipt)
        assertEquals("ALLOWED", new.data.first()[decisionKey])
        assertEquals(3, attempts) // a write retries the purge too (Astra r2 of PR #1250); it still fails here
        failCleanup = false
        assertEquals(EventReceiptConsentSnapshot("ALLOWED", receipt), source.snapshot.first())
        assertEquals(4, attempts)
        assertNull(old.data.first()[decisionKey])
        assertNull(old.data.first()[receiptKey])
    }

    @Test
    fun `unreadable legacy store cannot block reads or writes of device-local consent`() = runTest {
        val old = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("unreadable") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("Legacy store must not be written")
        }
        val source = EventReceiptConsentDataSource(store("consent_event_receipt"), old)
        assertEquals(EventReceiptConsentSnapshot(null, null), source.snapshot.first())
        source.setConsent("ALLOWED", receipt)
        assertEquals(EventReceiptConsentSnapshot("ALLOWED", receipt), source.snapshot.first())
    }

    @Test
    fun `repeated purge failures warn once under Consent without values or exception details`() = runTest {
        val warnings = mutableListOf<String>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                assertEquals(5, priority) // Android WARN
                assertEquals("Consent", tag)
                assertNull(t)
                warnings += message
            }
        }
        val old = object : DataStore<Preferences> {
            override val data = flow<Preferences> { throw IOException("ALLOWED private receipt") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("Unreadable store must not be written")
        }
        Timber.plant(tree)
        try {
            val source = EventReceiptConsentDataSource(store("consent_event_receipt"), old)
            repeat(3) { assertEquals(EventReceiptConsentSnapshot(null, null), source.snapshot.first()) }
            assertEquals(listOf("Legacy consent cleanup failed; will retry on next read"), warnings)
        } finally {
            Timber.uproot(tree)
        }
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
