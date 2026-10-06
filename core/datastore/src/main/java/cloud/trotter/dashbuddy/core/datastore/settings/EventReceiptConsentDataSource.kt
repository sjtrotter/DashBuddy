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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import cloud.trotter.dashbuddy.core.datastore.capability.ConsentReceiptJson
import timber.log.Timber

data class EventReceiptConsentSnapshot(val name: String?, val receipt: ConsentReceipt?)

/**
 * Storage for the wide-event-receipt consent (#1151), and its receipt in a device-local store.
 * Stores the raw enum NAME; the decode (and its fail-closed default) belongs to the repository in
 * `:core:data`, the one owner of the value.
 *
 * The decision lives in exactly one store: the backup-excluded consent store. Legacy app prefs
 * are backed up and may have come from another phone, so migrating them would restore consent
 * that must be collected on this device. We only purge those keys. An empty consent store means
 * UNDECIDED, so the prompt re-asks once after this upgrade as well as on a new phone.
 */
@Singleton
class EventReceiptConsentDataSource @Inject constructor(
    @param:EventReceiptConsentPreferences private val ds: DataStore<Preferences>,
    @param:AppPreferences private val appPreferences: DataStore<Preferences>,
) {
    private val purgeMutex = Mutex()
    private var purgeWarningLogged = false

    private object Keys {
        val EVENT_RECEIPT_CONSENT = stringPreferencesKey("event_receipt_consent")
        val EVENT_RECEIPT_RECEIPT = stringPreferencesKey("event_receipt_consent_receipt_json")
    }

    /**
     * Decision and record from ONE emission — the only read path, so each snapshot is internally consistent
     * (the repository's separate StateFlows may still be observed mid-update); `name` is null when nothing has
     * been saved (never asked). Malformed receipt JSON never changes the decision.
     */
    val snapshot: Flow<EventReceiptConsentSnapshot> = ds.data.onStart { purgeLegacyKeys() }.map { prefs ->
        EventReceiptConsentSnapshot(
            name = prefs[Keys.EVENT_RECEIPT_CONSENT],
            receipt = prefs[Keys.EVENT_RECEIPT_RECEIPT]?.let { encoded ->
                runCatching { ConsentReceiptJson.decodeFromString<ConsentReceipt>(encoded) }.getOrNull()
            },
        )
    }

    suspend fun setConsent(name: String, receipt: ConsentReceipt) {
        ds.edit {
            it[Keys.EVENT_RECEIPT_CONSENT] = name
            it[Keys.EVENT_RECEIPT_RECEIPT] = ConsentReceiptJson.encodeToString(receipt)
        }
    }

    /**
     * Best-effort, idempotent removal from backed-up preferences; never reads legacy values or
     * writes the consent store. Each collection retries cleanup, skipping the edit if both keys
     * are absent. Failure must not prevent reading the device-local decision or re-consenting.
     */
    private suspend fun purgeLegacyKeys() = purgeMutex.withLock {
        try {
            val old = appPreferences.data.first()
            if (old.contains(Keys.EVENT_RECEIPT_CONSENT) || old.contains(Keys.EVENT_RECEIPT_RECEIPT)) {
                appPreferences.edit {
                    it.remove(Keys.EVENT_RECEIPT_CONSENT)
                    it.remove(Keys.EVENT_RECEIPT_RECEIPT)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (!purgeWarningLogged) {
                purgeWarningLogged = true
                Timber.tag("Consent").w("Legacy consent cleanup failed; will retry on next read")
            }
        }
    }
}
