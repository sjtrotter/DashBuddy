package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.AppPreferences
import cloud.trotter.dashbuddy.core.datastore.di.EventReceiptConsentPreferences
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import cloud.trotter.dashbuddy.core.datastore.capability.ConsentReceiptJson

data class EventReceiptConsentSnapshot(val name: String?, val receipt: ConsentReceipt?)

/**
 * Storage for the wide-event-receipt consent (#1151), and its receipt in a device-local store.
 * Stores the raw enum NAME; the decode (and its fail-closed default) belongs to the repository in
 * `:core:data`, the one owner of the value.
 */
@Singleton
class EventReceiptConsentDataSource @Inject constructor(
    @param:EventReceiptConsentPreferences private val ds: DataStore<Preferences>,
    @param:AppPreferences private val appPreferences: DataStore<Preferences>,
) {
    private val migrationMutex = Mutex()
    private var migrated = false

    private object Keys {
        val EVENT_RECEIPT_CONSENT = stringPreferencesKey("event_receipt_consent")
        val EVENT_RECEIPT_RECEIPT = stringPreferencesKey("event_receipt_consent_receipt_json")
    }

    /**
     * Decision and record from ONE emission — the only read path, so each snapshot is internally consistent
     * (the repository's separate StateFlows may still be observed mid-update); `name` is null when nothing has
     * been saved (never asked). Malformed receipt JSON never changes the decision.
     */
    val snapshot: Flow<EventReceiptConsentSnapshot> = ds.data.onStart { migrateLegacyConsent() }.map { prefs ->
        EventReceiptConsentSnapshot(
            name = prefs[Keys.EVENT_RECEIPT_CONSENT],
            receipt = prefs[Keys.EVENT_RECEIPT_RECEIPT]?.let { encoded ->
                runCatching { ConsentReceiptJson.decodeFromString<ConsentReceipt>(encoded) }.getOrNull()
            },
        )
    }

    suspend fun setConsent(name: String, receipt: ConsentReceipt) {
        migrateLegacyConsent()
        ds.edit {
            it[Keys.EVENT_RECEIPT_CONSENT] = name
            it[Keys.EVENT_RECEIPT_RECEIPT] = ConsentReceiptJson.encodeToString(receipt)
        }
    }

    /**
     * Preserve this device's decision on upgrade without backing it up with economy preferences.
     * The destination write must succeed before removing the legacy keys. If cleanup fails, the
     * next attempt keeps the already-written destination and retries cleanup. Reads and writes
     * share this gate so migration cannot overwrite a newly collected decision.
     */
    private suspend fun migrateLegacyConsent() = migrationMutex.withLock {
        if (migrated) return@withLock
        val old = appPreferences.data.first()
        val name = old[Keys.EVENT_RECEIPT_CONSENT]
        val receipt = old[Keys.EVENT_RECEIPT_RECEIPT]
        if (name != null || receipt != null) {
            ds.edit { prefs ->
                if (prefs.asMap().isEmpty()) {
                    name?.let { prefs[Keys.EVENT_RECEIPT_CONSENT] = it }
                    receipt?.let { prefs[Keys.EVENT_RECEIPT_RECEIPT] = it }
                }
            }
            appPreferences.edit {
                it.remove(Keys.EVENT_RECEIPT_CONSENT)
                it.remove(Keys.EVENT_RECEIPT_RECEIPT)
            }
        }
        migrated = true
    }
}
