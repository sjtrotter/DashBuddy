package cloud.trotter.dashbuddy.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.appStateDataStore by preferencesDataStore(name = "app_state_prefs")

@Singleton
class AppStateDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val ds = context.appStateDataStore

    object Keys {
        val IS_FIRST_RUN = booleanPreferencesKey("is_first_run")
    }

    val isFirstRun: Flow<Boolean> = ds.data.map { it[Keys.IS_FIRST_RUN] ?: true }

    suspend fun update(transform: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        ds.edit { transform(it) }
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}