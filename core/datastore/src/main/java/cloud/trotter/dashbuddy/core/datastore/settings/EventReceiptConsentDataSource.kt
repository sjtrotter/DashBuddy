package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.AppPreferences
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class EventReceiptConsentSnapshot(val name: String?, val receipt: ConsentReceipt?)

/**
 * Storage for the wide-event-receipt consent (#1151), and its receipt in the app-preferences store.
 * Stores the raw enum NAME; the decode (and its fail-closed default) belongs to the repository in
 * `:core:data`, the one owner of the value.
 */
@Singleton
class EventReceiptConsentDataSource @Inject constructor(
    @param:AppPreferences private val ds: DataStore<Preferences>,
) {
    private object Keys {
        val EVENT_RECEIPT_CONSENT = stringPreferencesKey("event_receipt_consent")
        val EVENT_RECEIPT_RECEIPT = stringPreferencesKey("event_receipt_consent_receipt_json")
    }

    /** Decision and record from one emission; malformed receipt JSON never changes the decision. */
    val snapshot: Flow<EventReceiptConsentSnapshot> = ds.data.map { prefs ->
        EventReceiptConsentSnapshot(
            name = prefs[Keys.EVENT_RECEIPT_CONSENT],
            receipt = prefs[Keys.EVENT_RECEIPT_RECEIPT]?.let { encoded ->
                runCatching { Json.decodeFromString<ConsentReceipt>(encoded) }.getOrNull()
            },
        )
    }

    /** The stored decision name, or null when nothing has been saved (never asked). */
    val consent: Flow<String?> = snapshot.map { it.name }
    val receipt: Flow<ConsentReceipt?> = snapshot.map { it.receipt }

    suspend fun setConsent(name: String, receipt: ConsentReceipt) {
        ds.edit {
            it[Keys.EVENT_RECEIPT_CONSENT] = name
            it[Keys.EVENT_RECEIPT_RECEIPT] = Json.encodeToString(receipt)
        }
    }
}
