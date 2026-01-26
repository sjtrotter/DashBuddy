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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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

    // Keys
    private val keyMeters = doublePreferencesKey("odometer_accumulated_meters")
    private val keySessionAnchor = doublePreferencesKey("odometer_session_anchor") // <--- NEW

    // State
    private val _meters = MutableStateFlow(0.0) // Total Lifetime Meters
    private val _anchor = MutableStateFlow(0.0) // The reading when Dash started

    // Public Output: Session Miles (Reactive)
    val sessionMeters = combine(_meters, _anchor) { current, anchor ->
        (current - anchor).coerceAtLeast(0.0)
    }

    private var lastLocation: Location? = null
    private val metersToMiles = 0.000621371

    init {
        // Load BOTH values from disk on startup
        scope.launch {
            dataStore.data
                .catch { exception ->
                    if (exception is IOException) emit(emptyPreferences()) else throw exception
                }
                .collect { prefs ->
                    val savedMeters = prefs[keyMeters] ?: 0.0
                    val savedAnchor = prefs[keySessionAnchor] ?: 0.0

                    if (_meters.value != savedMeters) _meters.value = savedMeters
                    if (_anchor.value != savedAnchor) _anchor.value = savedAnchor
                }
        }
    }

    /**
     * Call this ONLY when a NEW dash starts (Transition from Idle -> Active).
     * It sets the "Trip A" counter to 0 by moving the anchor to the current total.
     */
    fun resetSession() {
        Log.i("OdometerRepo", "Resetting Session Odometer")
        val currentTotal = _meters.value
        _anchor.value = currentTotal
        saveAnchor(currentTotal)
    }

    fun startTracking() {
        if (trackingJob?.isActive == true) return
        Log.i("OdometerRepo", "Starting GPS Tracking...")
        trackingJob = scope.launch {
            locationDataSource.locationUpdates.collect { processLocation(it) }
        }
    }

    fun stopTracking() {
        if (trackingJob?.isActive == true) {
            Log.i("OdometerRepo", "Stopping GPS Tracking.")
            trackingJob?.cancel()
            trackingJob = null
            lastLocation = null
        }
    }

    private fun processLocation(location: Location) {
        if (location.accuracy > 25f) return

        if (lastLocation != null) {
            val distanceMeters = location.distanceTo(lastLocation!!).toDouble()
            if (distanceMeters > 5) {
                addMeters(distanceMeters)
            }
        }
        lastLocation = location
    }

    private fun addMeters(delta: Double) {
        val newTotal = _meters.value + delta
        _meters.value = newTotal
        saveMeters(newTotal)
    }

    // --- Persistence ---

    private fun saveMeters(newTotal: Double) {
        scope.launch {
            try {
                dataStore.edit { it[keyMeters] = newTotal }
            } catch (e: Exception) {
                Log.e("OdometerRepo", "Failed to save meters", e)
            }
        }
    }

    private fun saveAnchor(newAnchor: Double) {
        scope.launch {
            try {
                dataStore.edit { it[keySessionAnchor] = newAnchor }
            } catch (e: Exception) {
                Log.e("OdometerRepo", "Failed to save anchor", e)
            }
        }
    }

    // --- Helpers ---
    fun getCurrentSessionMiles(): Double =
        (_meters.value - _anchor.value).coerceAtLeast(0.0) * metersToMiles

    fun getCurrentMiles(): Double = _meters.value * metersToMiles
}