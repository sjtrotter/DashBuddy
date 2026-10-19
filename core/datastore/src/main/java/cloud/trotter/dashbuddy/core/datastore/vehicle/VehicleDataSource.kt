package cloud.trotter.dashbuddy.core.datastore.vehicle

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.VehiclePreferences
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VehicleDataSource @Inject constructor(
    @param:VehiclePreferences private val ds: DataStore<Preferences>,
) {
    data class StoredVehicles(val json: String?, val activeId: String?)
    private object Keys {
        val VEHICLES = stringPreferencesKey("vehicles_json")
        val ACTIVE = stringPreferencesKey("active_vehicle_id")
    }
    // Consumers needing both use the same DataStore snapshot, never two independently emitted keys.
    val stored = ds.data.map { StoredVehicles(it[Keys.VEHICLES], it[Keys.ACTIVE]) }

    suspend fun save(json: String, activeId: String?) {
        ds.edit {
            it[Keys.VEHICLES] = json
            if (activeId == null) it.remove(Keys.ACTIVE) else it[Keys.ACTIVE] = activeId
        }
    }
}
