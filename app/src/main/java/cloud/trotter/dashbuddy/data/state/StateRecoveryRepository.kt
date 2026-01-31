package cloud.trotter.dashbuddy.data.state

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateRecoveryRepository @Inject constructor(
    private val dataStore: DataStore<Preferences> // Injected from AppModule
) {
    private val keyJson = stringPreferencesKey("crash_recovery_state_json")
    private val keyTimestamp = longPreferencesKey("crash_recovery_timestamp")

    private val expiryMs = 30 * 60 * 1000L // 30 Minutes

    /**
     * Persists the State Machine snapshot to disk.
     */
    suspend fun saveState(json: String) {
        dataStore.edit { prefs ->
            prefs[keyJson] = json
            prefs[keyTimestamp] = System.currentTimeMillis()
        }
    }

    /**
     * Retrieves the snapshot only if it is fresh (< 30 mins old).
     * Returns null if expired or missing.
     */
    suspend fun getFreshState(): String? {
        val prefs = dataStore.data.first()
        val json = prefs[keyJson] ?: return null
        val timestamp = prefs[keyTimestamp] ?: 0L

        if (System.currentTimeMillis() - timestamp > expiryMs) {
            clearState() // Cleanup old data
            return null
        }
        return json
    }

    suspend fun clearState() {
        dataStore.edit { prefs ->
            prefs.remove(keyJson)
            prefs.remove(keyTimestamp)
        }
    }
}