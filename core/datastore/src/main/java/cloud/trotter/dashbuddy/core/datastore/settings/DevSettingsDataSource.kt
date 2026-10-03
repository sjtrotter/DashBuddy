package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
        val CENSUS_UPLOAD_ENABLED = booleanPreferencesKey("census_upload_enabled")
        val CENSUS_BASE_URL = stringPreferencesKey("census_base_url")
        val CENSUS_NEXT_ALLOWED_AT = longPreferencesKey("census_next_allowed_at_millis")
        val CENSUS_POLICY = stringPreferencesKey("census_policy")
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

    val censusUploadEnabled: Flow<Boolean> = ds.data.map { it[Keys.CENSUS_UPLOAD_ENABLED] ?: false }
    val censusBaseUrl: Flow<String> = ds.data.map {
        it[Keys.CENSUS_BASE_URL] ?: "https://census.dashbuddy.trotter.cloud"
    }
    val nextAllowedAtMillis: Flow<Long> = ds.data.map { it[Keys.CENSUS_NEXT_ALLOWED_AT] ?: 0L }
    val censusPolicy: Flow<String?> = ds.data.map { it[Keys.CENSUS_POLICY] }

    suspend fun setCensusUploadEnabled(enabled: Boolean) {
        ds.edit { it[Keys.CENSUS_UPLOAD_ENABLED] = enabled }
    }

    suspend fun setCensusBaseUrl(url: String): Boolean {
        if (!Regex("^https://[a-z0-9.-]+(:[0-9]{2,5})?$").matches(url)) return false
        ds.edit { it[Keys.CENSUS_BASE_URL] = url }
        return true
    }

    suspend fun setNextAllowedAtMillis(value: Long) {
        ds.edit { it[Keys.CENSUS_NEXT_ALLOWED_AT] = value }
    }

    suspend fun setCensusPolicy(value: String) {
        ds.edit { it[Keys.CENSUS_POLICY] = value }
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}