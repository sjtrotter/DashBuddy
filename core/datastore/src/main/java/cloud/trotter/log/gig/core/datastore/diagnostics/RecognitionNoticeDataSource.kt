package cloud.trotter.log.gig.core.datastore.diagnostics

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.log.gig.core.datastore.di.RecognitionNoticePreferences
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * When each delivery app's recognition notice last posted (#1560): one JSON object of platform wire →
 * epoch millis. The only persisted part of a recognition incident; the incidents themselves live in memory.
 *
 * No logging and no fallback here: a decode failure throws, and the owner (`RecognitionHealthMonitor`)
 * fails open.
 */
@Singleton
class RecognitionNoticeDataSource @Inject constructor(
    @param:RecognitionNoticePreferences private val ds: DataStore<Preferences>,
) {
    private object Keys {
        val LAST_NOTICE_AT = stringPreferencesKey("last_notice_at_json")
    }

    /** The stored map; empty when nothing was ever stored. Throws on a stored value that does not decode. */
    suspend fun load(): Map<String, Long> {
        val encoded = ds.data.first()[Keys.LAST_NOTICE_AT] ?: return emptyMap()
        return NoticeJson.decodeFromString<Map<String, Long>>(encoded)
    }

    suspend fun save(lastNoticeAt: Map<String, Long>) {
        ds.edit { prefs -> prefs[Keys.LAST_NOTICE_AT] = NoticeJson.encodeToString(lastNoticeAt) }
    }

    private companion object {
        /** Lenient on unknown keys, like `ConsentReceiptJson`; a top-level map has none, so this only future-proofs. */
        val NoticeJson: Json = Json { ignoreUnknownKeys = true }
    }
}
