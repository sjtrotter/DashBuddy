package cloud.trotter.dashbuddy.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recoveryDataStore by preferencesDataStore(name = "state_recovery_prefs")

@Singleton
class StateRecoveryDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val ds = context.recoveryDataStore

    private val keyJson = stringPreferencesKey("crash_recovery_state_json")
    private val keyTimestamp = longPreferencesKey("crash_recovery_timestamp")

    suspend fun saveState(json: String, timestamp: Long) {
        ds.edit { prefs ->
            prefs[keyJson] = json
            prefs[keyTimestamp] = timestamp
        }
    }

    suspend fun getJson(): String? = ds.data.first()[keyJson]
    suspend fun getTimestamp(): Long = ds.data.first()[keyTimestamp] ?: 0L

    suspend fun clearState() {
        ds.edit { prefs ->
            prefs.remove(keyJson)
            prefs.remove(keyTimestamp)
        }
    }
}