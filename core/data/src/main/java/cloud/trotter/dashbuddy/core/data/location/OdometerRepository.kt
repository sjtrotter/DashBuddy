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
    /**
     * The cumulative device odometer total (meters). THE `metadata.odometer` input the analytics
     * projector reads (#314) — a raw cumulative reading. #438 B5 does NOT change its semantics; only
     * the per-session anchor bookkeeping below changed.
     */
    private val _meters = MutableStateFlow(0.0)

    /**
     * #438 B5: per-session start anchors (sessionId → odometer meters at session start). Replaces the
     * single global `_anchor`, so two concurrent sessions accrue miles independently and a second
     * session starting can't zero the first's total.
     */
    private val _anchors = MutableStateFlow<Map<String, Double>>(emptyMap())

    /** The session the no-arg [sessionMilesFlow] tracks (the HUD's "session miles" view). */
    private val _currentSessionId = MutableStateFlow<String?>(null)

    // Public Output: Reactive Session Miles for the CURRENT session (the HUD's "session miles").
    val sessionMilesFlow: Flow<Double> =
        combine(_meters, _anchors, _currentSessionId) { total, anchors, sid ->
            val anchor = sid?.let { anchors[it] } ?: 0.0
            (total - anchor).coerceAtLeast(0.0) * metersToMiles
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
            // #438 B5: restore the current session's anchor so HUD miles survive a crash.
            val currentId = odometerLocalDataSource.currentSessionIdFlow.first()
            if (currentId != null) {
                _currentSessionId.value = currentId
                val anchor = odometerLocalDataSource.sessionAnchorFlow(currentId).first()
                if (anchor != null) _anchors.value = mapOf(currentId to anchor)
            }
        }
    }

    /**
     * #438 B5: anchor [sessionId] to the current cumulative total the first time it starts, and mark
     * it the current session. **Idempotent** — a grace-resume that re-arms the session MUST NOT move
     * an existing anchor (that would zero the session's accrued miles, the exact concurrency bug this
     * replaces). Replaces the old global `resetSession()`.
     */
    fun startSessionTracking(sessionId: String) {
        _currentSessionId.value = sessionId
        if (_anchors.value.containsKey(sessionId)) return
        val anchor = _meters.value
        _anchors.value = _anchors.value + (sessionId to anchor)
        scope.launch { odometerLocalDataSource.saveSessionAnchor(sessionId, anchor) }
    }

    /**
     * #438 B5: per-session miles — session A and B accrue independently. A session with no anchor yet
     * reads 0 (anchor defaults to the current total). #528 consumes these + the overlap flag for
     * per-drop / GPS $/mi.
     */
    fun sessionMilesFlow(sessionId: String): Flow<Double> =
        combine(_meters, _anchors) { total, anchors ->
            val anchor = anchors[sessionId] ?: total
            (total - anchor).coerceAtLeast(0.0) * metersToMiles
        }

    fun startTracking() {
        if (trackingJob?.isActive == true) return
        Timber.tag(TAG).i("Starting GPS Tracking...")
        trackingJob = scope.launch {
            locationDataSource.locationUpdates.collect { processLocation(it) }
        }
    }

    fun stopTracking() {
        if (trackingJob?.isActive == true) {
            Timber.tag(TAG).i("Stopping GPS Tracking.")
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

    /** Miles accrued by [sessionId] (its total delta since its anchor). */
    fun getCurrentSessionMiles(sessionId: String): Double {
        val anchor = _anchors.value[sessionId] ?: _meters.value
        return (_meters.value - anchor).coerceAtLeast(0.0) * metersToMiles
    }

    /**
     * The raw cumulative device odometer reading (miles) — the projector's `metadata.odometer` input
     * (#314). Unaffected by the #438 B5 per-session anchor change (it reads [_meters] only).
     */
    fun getCurrentMiles(): Double = _meters.value * metersToMiles

    private companion object {
        private const val TAG = "Odometer"
    }
}
