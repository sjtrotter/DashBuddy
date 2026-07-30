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
        val IS_FIRST_RUN = booleanPreferencesKey("is_first_run")

        /**
         * Whether the dasher has already been shown the "recognition is English-only"
         * notice (#938). App-lifecycle state, not a setting — it belongs here rather than
         * in app prefs (a user-editable value) or dev settings (developer-only).
         */
        val LOCALE_BOUNDARY_NOTICE_SHOWN = booleanPreferencesKey("locale_boundary_notice_shown")
    }

    // Default to true if the key doesn't exist yet
    val isFirstRun: Flow<Boolean> = ds.data.map { it[Keys.IS_FIRST_RUN] ?: true }

    /** False until the #938 English-locale notice has been posted once on this install. */
    val localeBoundaryNoticeShown: Flow<Boolean> =
        ds.data.map { it[Keys.LOCALE_BOUNDARY_NOTICE_SHOWN] ?: false }

    // Safely encapsulated write action
    suspend fun setFirstRunComplete() {
        ds.edit { it[Keys.IS_FIRST_RUN] = false }
    }

    suspend fun setLocaleBoundaryNoticeShown() {
        ds.edit { it[Keys.LOCALE_BOUNDARY_NOTICE_SHOWN] = true }
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}