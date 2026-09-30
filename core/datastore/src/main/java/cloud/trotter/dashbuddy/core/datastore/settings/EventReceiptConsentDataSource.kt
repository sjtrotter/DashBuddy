package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for the wide-event-receipt consent (#1151), a single key in the app-preferences store.
 * Stores the raw enum NAME; the decode (and its fail-closed default) belongs to the repository in
 * `:core:data`, the one owner of the value.
 */
@Singleton
class EventReceiptConsentDataSource @Inject constructor(
    @param:AppPreferences private val ds: DataStore<Preferences>,
) {
    private object Keys {
        val EVENT_RECEIPT_CONSENT = stringPreferencesKey("event_receipt_consent")
    }

    /** The stored decision name, or null when nothing has been saved (never asked). */
    val consent: Flow<String?> = ds.data.map { it[Keys.EVENT_RECEIPT_CONSENT] }

    suspend fun setConsent(name: String) {
        ds.edit { it[Keys.EVENT_RECEIPT_CONSENT] = name }
    }
}
