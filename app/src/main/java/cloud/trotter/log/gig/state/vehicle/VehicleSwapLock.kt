package cloud.trotter.log.gig.state.vehicle

import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicles
import cloud.trotter.log.gig.domain.model.vehicle.LiveDash
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerCue
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

internal fun AppState.liveVehicleDashes(): List<LiveDash> =
    regions.platforms.values.mapNotNull { it.session }.map { LiveDash(it.sessionId, it.startedAt) }

@Singleton
class VehicleSwapLock @Inject constructor(
    private val stateManager: StateManagerV2,
    private val clock: Clock,
) {
    fun locked(): Boolean = DashVehicles.swapLocked(
        stateManager.state.value.liveVehicleDashes(), clock.millis(), OdometerCue.WINDOW_MS)
}
