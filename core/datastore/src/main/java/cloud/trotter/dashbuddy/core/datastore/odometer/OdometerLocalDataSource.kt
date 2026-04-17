package cloud.trotter.dashbuddy.core.datastore.odometer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import cloud.trotter.dashbuddy.core.datastore.di.OdometerPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OdometerLocalDataSource @Inject constructor(
    @param:OdometerPreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        val TOTAL_METERS = doublePreferencesKey("total_meters")
        val SESSION_ANCHOR = doublePreferencesKey("session_anchor")
    }

    val totalMetersFlow: Flow<Double> = ds.data.map { it[Keys.TOTAL_METERS] ?: 0.0 }
    val sessionAnchorFlow: Flow<Double> = ds.data.map { it[Keys.SESSION_ANCHOR] ?: 0.0 }

    suspend fun saveTotalMeters(meters: Double) {
        ds.edit { it[Keys.TOTAL_METERS] = meters }
    }

    suspend fun saveSessionAnchor(anchor: Double) {
        ds.edit { it[Keys.SESSION_ANCHOR] = anchor }
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}