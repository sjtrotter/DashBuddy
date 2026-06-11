package cloud.trotter.dashbuddy.core.datastore.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.PlatformPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlatformPreferencesDataSource @Inject constructor(
    @param:PlatformPreferences private val ds: DataStore<Preferences>,
) {
    private object Keys {
        val ENABLED_PLATFORMS = stringSetPreferencesKey("enabled_platforms")
    }

    /**
     * Flow of enabled platform wire names, or null if no preference has been saved yet
     * (meaning "use installed-app detection as default").
     */
    val enabledPlatforms: Flow<Set<String>?> = ds.data.map { it[Keys.ENABLED_PLATFORMS] }

    /** Persist the set of enabled platform wire names. */
    suspend fun setEnabledPlatforms(platforms: Set<String>) {
        ds.edit { it[Keys.ENABLED_PLATFORMS] = platforms }
    }

    /**
     * Atomically transform the enabled set (#364). [transform] receives the
     * currently-saved set (null = nothing saved yet) INSIDE the DataStore
     * edit, so concurrent toggles can't interleave a read-modify-write.
     */
    suspend fun updateEnabledPlatforms(transform: (Set<String>?) -> Set<String>) {
        ds.edit { prefs ->
            prefs[Keys.ENABLED_PLATFORMS] = transform(prefs[Keys.ENABLED_PLATFORMS])
        }
    }
}
