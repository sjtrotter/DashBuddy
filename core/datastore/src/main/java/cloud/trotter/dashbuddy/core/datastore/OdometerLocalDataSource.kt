package cloud.trotter.dashbuddy.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

// Temporary instance for Sub-Issue 1 (Will be moved to Hilt in Sub-Issue 2)
private val Context.odometerDataStore by preferencesDataStore(name = "odometer_prefs")

@Singleton
class OdometerLocalDataSource @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val keyMeters = doublePreferencesKey("odometer_accumulated_meters")
    private val keySessionAnchor = doublePreferencesKey("odometer_session_anchor")

    private val preferencesFlow = context.odometerDataStore.data.catch { exception ->
        if (exception is IOException) emit(emptyPreferences()) else throw exception
    }

    val totalMetersFlow: Flow<Double> = preferencesFlow.map { it[keyMeters] ?: 0.0 }
    val sessionAnchorFlow: Flow<Double> = preferencesFlow.map { it[keySessionAnchor] ?: 0.0 }

    suspend fun saveTotalMeters(meters: Double) {
        try {
            context.odometerDataStore.edit { it[keyMeters] = meters }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save total meters to disk")
        }
    }

    suspend fun saveSessionAnchor(anchor: Double) {
        try {
            context.odometerDataStore.edit { it[keySessionAnchor] = anchor }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save session anchor to disk")
        }
    }
}