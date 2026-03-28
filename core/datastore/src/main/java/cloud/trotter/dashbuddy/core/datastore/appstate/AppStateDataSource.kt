package cloud.trotter.dashbuddy.core.datastore.appstate

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import cloud.trotter.dashbuddy.core.datastore.di.AppStatePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStateDataSource @Inject constructor(
    @param:AppStatePreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        // The only key this DataSource actually needs!
        val IS_FIRST_RUN = booleanPreferencesKey("is_first_run")
    }

    // Default to true if the key doesn't exist yet
    val isFirstRun: Flow<Boolean> = ds.data.map { it[Keys.IS_FIRST_RUN] ?: true }

    // Safely encapsulated write action
    suspend fun setFirstRunComplete() {
        ds.edit { it[Keys.IS_FIRST_RUN] = false }
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}