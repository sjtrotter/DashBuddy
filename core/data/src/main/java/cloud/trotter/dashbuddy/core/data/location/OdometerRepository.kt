package cloud.trotter.dashbuddy.core.data.location

import cloud.trotter.dashbuddy.core.datastore.odometer.OdometerLocalDataSource
import cloud.trotter.dashbuddy.core.location.LocationDataSource
import cloud.trotter.dashbuddy.domain.model.location.Coordinates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import cloud.trotter.dashbuddy.domain.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first

@Singleton
class OdometerRepository @Inject constructor(
    private val odometerLocalDataSource: OdometerLocalDataSource,
    private val locationDataSource: LocationDataSource,
    @IoDispatcher ioDispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())
    private var trackingJob: Job? = null

    // State
    private val _meters = MutableStateFlow(0.0)
    private val _anchor = MutableStateFlow(0.0)

    val sessionMeters = combine(_meters, _anchor) { current, anchor ->
        (current - anchor).coerceAtLeast(0.0)
    }

    // Public Output: Reactive Session Miles
    val sessionMilesFlow: Flow<Double> = sessionMeters.map { meters ->
        meters * metersToMiles
    }

    private var lastCoords: Coordinates? = null
    private val metersToMiles = 0.000621371

    init {
        // Read persisted values ONCE at startup (#364): the old collectors let a
        // stale DataStore emission (from an in-flight save echo) transiently
        // REGRESS the live value mid-session. The live StateFlows are the
        // single source of truth after startup; saves flow one-way to disk.
        scope.launch {
            _meters.value = odometerLocalDataSource.totalMetersFlow.first()
            _anchor.value = odometerLocalDataSource.sessionAnchorFlow.first()
        }
    }

    fun resetSession() {
        Timber.Forest.i("Resetting Session Odometer")
        val currentTotal = _meters.value
        _anchor.value = currentTotal
        scope.launch { odometerLocalDataSource.saveSessionAnchor(currentTotal) }
    }

    fun startTracking() {
        if (trackingJob?.isActive == true) return
        Timber.Forest.i("Starting GPS Tracking...")
        trackingJob = scope.launch {
            locationDataSource.locationUpdates.collect { processLocation(it) }
        }
    }

    fun stopTracking() {
        if (trackingJob?.isActive == true) {
            Timber.Forest.i("Stopping GPS Tracking.")
            trackingJob?.cancel()
            trackingJob = null
            lastCoords = null
        }
    }

    private fun processLocation(coords: Coordinates) {
        if (lastCoords != null) {
            val distanceMeters = coords.distanceTo(lastCoords!!)
            if (distanceMeters > 5) {
                addMeters(distanceMeters)
            }
        }
        lastCoords = coords
    }

    private fun addMeters(delta: Double) {
        val newTotal = _meters.value + delta
        _meters.value = newTotal
        scope.launch { odometerLocalDataSource.saveTotalMeters(newTotal) }
    }

    fun getCurrentSessionMiles(): Double =
        (_meters.value - _anchor.value).coerceAtLeast(0.0) * metersToMiles

    fun getCurrentMiles(): Double = _meters.value * metersToMiles
}