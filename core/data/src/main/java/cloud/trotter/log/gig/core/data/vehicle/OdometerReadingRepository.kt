package cloud.trotter.log.gig.core.data.vehicle

import cloud.trotter.log.gig.core.data.event.AppEventRepo
import cloud.trotter.log.gig.core.data.location.OdometerRepository
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.OdometerReadingPayload
import cloud.trotter.log.gig.domain.model.vehicle.LastOdometerReading
import cloud.trotter.log.gig.domain.model.vehicle.OdometerReadingLimit
import cloud.trotter.log.gig.domain.model.vehicle.VehicleOdometerReading
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OdometerReadingRepository @Inject constructor(
    private val appEventRepo: AppEventRepo,
    private val odometer: OdometerRepository,
    private val clock: Clock,
) {
    /** The event repository deduplicates the row list before decoding; see `getSequencedEventsByType`. */
    val readings = appEventRepo.getSequencedEventsByType(AppEventType.ODOMETER_READING).map { events ->
        events.mapNotNull { sequenced ->
            val event = sequenced.event
            val p = event.payload as? OdometerReadingPayload ?: return@mapNotNull null
            VehicleOdometerReading(p.vehicleId,
                LastOdometerReading(p.readingMiles, p.gpsTotalMiles, event.occurredAt), sequenced.sequenceId)
        }
    }

    suspend fun record(vehicleId: String, readingMiles: Int): Long {
        require(vehicleId.isNotBlank())
        require(OdometerReadingLimit.isValid(readingMiles))
        val occurredAt = clock.millis()
        val gpsTotalMiles = odometer.totalMiles()
        return appEventRepo.appendUserEvent(
            AppEvent(AppEventType.ODOMETER_READING, occurredAt, sessionId = null,
                payload = OdometerReadingPayload(vehicleId, readingMiles, gpsTotalMiles)),
            metadataJson = null,
        )
    }
}
