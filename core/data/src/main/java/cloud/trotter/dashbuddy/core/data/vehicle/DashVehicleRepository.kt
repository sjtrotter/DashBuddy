package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DashVehiclePayload
import cloud.trotter.dashbuddy.domain.model.vehicle.DashVehicleKind
import cloud.trotter.dashbuddy.domain.model.vehicle.DashVehicleStamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DashVehicleRepository @Inject constructor(
    private val appEventRepo: AppEventRepo,
    private val odometer: OdometerRepository,
    private val clock: Clock,
) {
    val stamps: Flow<List<DashVehicleStamp>> = appEventRepo.getSequencedEventsByType(AppEventType.DASH_VEHICLE).map { events ->
        events.mapNotNull { sequenced ->
            val event = sequenced.event
            val p = event.payload as? DashVehiclePayload ?: return@mapNotNull null
            DashVehicleStamp(p.sessionId, p.vehicleId, p.gpsTotalMiles, event.occurredAt,
                sequenced.sequenceId, DashVehicleKind.fromName(p.kind))
        }
    }

    suspend fun record(sessionId: String, vehicleId: String?, kind: DashVehicleKind): Long {
        require(sessionId.isNotBlank())
        require(vehicleId == null || vehicleId.isNotBlank())
        val occurredAt = clock.millis()
        return appEventRepo.appendUserEvent(
            AppEvent(AppEventType.DASH_VEHICLE, occurredAt, sessionId = null,
                payload = DashVehiclePayload(sessionId, vehicleId, odometer.totalMiles(), kind.name)),
            metadataJson = null,
        )
    }
}
