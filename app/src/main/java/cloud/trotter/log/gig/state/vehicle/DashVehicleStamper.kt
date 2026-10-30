package cloud.trotter.log.gig.state.vehicle

import android.content.Context
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.core.data.vehicle.DashVehicleRepository
import cloud.trotter.log.gig.core.data.vehicle.GarageRepository
import cloud.trotter.log.gig.core.state.StateManagerV2
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicleAction
import cloud.trotter.log.gig.domain.model.vehicle.DashVehicles
import cloud.trotter.log.gig.feature.bubble.hud.odometer.OdometerCue
import cloud.trotter.log.gig.ui.bubble.BubbleManager
import cloud.trotter.log.gig.ui.components.vehicle.displayName
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/** Observes session identity without changing the state machine or its effects. */
@Singleton
class DashVehicleStamper @Inject constructor(
    private val stateManager: StateManagerV2,
    private val garageRepository: GarageRepository,
    private val repository: DashVehicleRepository,
    private val bubbleManager: BubbleManager,
    private val clock: Clock,
    @param:ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val postedNext = mutableSetOf<Pair<String, String>>()

    fun start(scope: CoroutineScope) {
        val liveDashes = stateManager.state.map { it.liveVehicleDashes() }.distinctUntilChanged()
        val activeVehicleId = garageRepository.garage.map { if (it.readable) it.active?.id else null }
            .distinctUntilChanged()
        scope.launch {
            combine(liveDashes, activeVehicleId) { live, active -> live to active }.collect { (live, active) ->
                try {
                    mutex.withLock {
                        postedNext.removeAll { (session, _) -> live.none { it.sessionId == session } }
                        val actions = DashVehicles.decide(live, active, repository.stamps.first(),
                            now = clock.millis(), windowMs = OdometerCue.WINDOW_MS)
                        for (action in actions) when (action) {
                            is DashVehicleAction.Stamp -> {
                                repository.record(action.sessionId, action.vehicleId, action.kind)
                                Timber.tag("DashVehicle").i("dash vehicle stamped kind=%s", action.kind.name)
                                Timber.tag("DashVehicle").d("session=%s vehicle=%s", action.sessionId, action.vehicleId)
                            }
                            is DashVehicleAction.ThisDash -> post(action.sessionId, action.vehicleId, R.string.bubble_chat_vehicle_this_dash)
                            is DashVehicleAction.NextDash -> {
                                val pair = action.sessionId to action.vehicleId
                                if (pair !in postedNext && post(action.sessionId, action.vehicleId, R.string.bubble_chat_vehicle_next_dash)) {
                                    postedNext.add(pair)
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Timber.tag("DashVehicle").w("%s", error.javaClass.simpleName)
                }
            }
        }
    }

    private suspend fun post(sessionId: String, vehicleId: String, resource: Int): Boolean {
        val garage = garageRepository.garage.first()
        val vehicle = garage.takeIf { it.readable }?.vehicles?.firstOrNull { it.id == vehicleId } ?: return false
        bubbleManager.postVehicleLine(sessionId, context.getString(resource, vehicle.displayName(context)))
        return true
    }
}
