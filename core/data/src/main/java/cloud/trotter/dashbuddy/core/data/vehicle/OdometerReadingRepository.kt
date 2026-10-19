package cloud.trotter.dashbuddy.core.data.vehicle

import cloud.trotter.dashbuddy.core.data.event.AppEventRepo
import cloud.trotter.dashbuddy.core.data.location.OdometerRepository
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OdometerReadingPayload
import cloud.trotter.dashbuddy.domain.model.vehicle.LastOdometerReading
import cloud.trotter.dashbuddy.domain.model.vehicle.VehicleOdometerReading
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OdometerReadingRepository @Inject constructor(
    private val appEventRepo: AppEventRepo,
    private val odometer: OdometerRepository,
) {
    val readings = appEventRepo.getEventsByType(AppEventType.ODOMETER_READING).map { events ->
        events.mapNotNull { event ->
            val p = event.payload as? OdometerReadingPayload ?: return@mapNotNull null
            VehicleOdometerReading(p.vehicleId, LastOdometerReading(p.readingMiles, p.gpsTotalMiles, event.occurredAt))
        }
    }

    fun lastReading(vehicleId: String) = readings.map { list ->
        list.filter { it.vehicleId == vehicleId }.maxByOrNull { it.reading.occurredAt }?.reading
    }

    suspend fun record(vehicleId: String, readingMiles: Int, occurredAt: Long): Long {
        require(vehicleId.isNotBlank())
        require(readingMiles in 1..999_999)
        return appEventRepo.appendUserEvent(
            AppEvent(AppEventType.ODOMETER_READING, occurredAt, sessionId = null,
                payload = OdometerReadingPayload(vehicleId, readingMiles, odometer.totalMiles())),
            metadataJson = null,
        )
    }
}
