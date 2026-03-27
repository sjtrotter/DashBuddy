package cloud.trotter.dashbuddy.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.devSettingsDataStore by preferencesDataStore(name = "dev_settings")

@Singleton
class DevSettingsDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val ds = context.devSettingsDataStore

    object Keys {
        val LOG_LEVEL = intPreferencesKey("app_log_level")
        val IS_DEV_MODE_UNLOCKED = booleanPreferencesKey("is_dev_mode_unlocked")
    }

    val logLevel: Flow<Int?> = ds.data.map { it[Keys.LOG_LEVEL] }
    val isDevModeUnlocked: Flow<Boolean?> = ds.data.map { it[Keys.IS_DEV_MODE_UNLOCKED] }

    suspend fun update(transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ds.edit { transform(it) }
    }
}