package cloud.trotter.dashbuddy.data.location

import android.location.Location
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.log.Logger as Log

@Singleton
class OdometerRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val locationDataSource: LocationDataSource
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var trackingJob: Job? = null

    // Key: Storing RAW METERS (Precision)
    private val keyMeters = doublePreferencesKey("odometer_accumulated_meters")

    // State: Source of truth is RAM, backed by Disk
    private val _meters = MutableStateFlow(0.0)
    val meters = _meters.asStateFlow()

    private var lastLocation: Location? = null

    // Constant for UI conversion
    private val metersToMiles = 0.000621371

    init {
        // --- REACTIVE BINDING ---
        // This ensures _meters is always in sync with DataStore on startup.
        scope.launch {
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) {
                        Log.e("OdometerRepo", "Error reading preferences", exception)
                        emit(emptyPreferences())
                    } else {
                        throw exception
                    }
                }
                .map { preferences ->
                    preferences[keyMeters] ?: 0.0
                }
                .collect { savedMeters ->
                    // Only update if changed (prevents loops)
                    if (_meters.value != savedMeters) {
                        _meters.value = savedMeters
                    }
                }
        }
    }

    fun startTracking() {
        if (trackingJob?.isActive == true) return

        Log.i("OdometerRepo", "Starting Odometer Job...")

        trackingJob = scope.launch {
            locationDataSource.locationUpdates.collect { location ->
                processLocation(location)
            }
        }
    }

    fun stopTracking() {
        if (trackingJob?.isActive == true) {
            Log.i("OdometerRepo", "Stopping Odometer Job.")
            trackingJob?.cancel()
            trackingJob = null
            lastLocation = null
        }
    }

    private fun processLocation(location: Location) {
        // 1. Filter Bad GPS
        if (location.accuracy > 25f) return

        // 2. Calculate Delta
        if (lastLocation != null) {
            val distanceMeters = location.distanceTo(lastLocation!!).toDouble()

            // Noise Filter > 5m
            if (distanceMeters > 5) {
                addMeters(distanceMeters)
            }
        } else {
            Log.i("OdometerRepo", "Anchor set: ${location.latitude}")
        }
        lastLocation = location
    }

    private fun addMeters(delta: Double) {
        // Update Memory immediately (for UI responsiveness)
        val newTotal = _meters.value + delta
        _meters.value = newTotal

        // Save to Disk (Async)
        saveState(newTotal)
    }

    private fun saveState(newTotal: Double) {
        scope.launch {
            try {
                dataStore.edit { preferences ->
                    preferences[keyMeters] = newTotal
                }
            } catch (e: Exception) {
                Log.e("OdometerRepo", "Failed to save odometer", e)
            }
        }
    }

    // --- Helpers ---

    // Calculated on the fly for UI/Logs
    fun getCurrentMiles(): Double = _meters.value * metersToMiles
}