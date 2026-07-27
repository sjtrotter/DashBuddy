package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.DevSettingsPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DevSettingsDataSource @Inject constructor(
    @param:DevSettingsPreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        val IS_DEV_MODE_UNLOCKED = booleanPreferencesKey("is_dev_mode_unlocked")
        val LOG_LEVEL = intPreferencesKey("log_level")
        val BUBBLE_SESSION_MODE = stringPreferencesKey("bubble_session_mode")
    }

    val isDevModeUnlocked: Flow<Boolean?> = ds.data.map { it[Keys.IS_DEV_MODE_UNLOCKED] }
    val logLevel: Flow<Int?> = ds.data.map { it[Keys.LOG_LEVEL] }

    /**
     * The bubble's session-presentation experiment switch (#867), stored as the enum's wire string.
     * Raw here — the repository owns the fail-closed decode so the datastore stays type-free.
     */
    val bubbleSessionMode: Flow<String?> = ds.data.map { it[Keys.BUBBLE_SESSION_MODE] }

    suspend fun setDevModeUnlocked(unlocked: Boolean) {
        ds.edit { it[Keys.IS_DEV_MODE_UNLOCKED] = unlocked }
    }

    suspend fun setLogLevel(priority: Int) {
        ds.edit { it[Keys.LOG_LEVEL] = priority }
    }

    suspend fun setBubbleSessionMode(wire: String) {
        ds.edit { it[Keys.BUBBLE_SESSION_MODE] = wire }
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}