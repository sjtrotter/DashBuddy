package cloud.trotter.dashbuddy.domain.model.vehicle

import kotlin.math.ceil
import kotlin.math.max

data class OdometerCheckInput(
    val readingMiles: Int?,
    val vehicle: Vehicle?,
    val last: LastOdometerReading?,
    val gpsTotalNow: Double?,
    val now: Long,
    val readings: List<VehicleOdometerReading>,
)

sealed interface OdometerCheck {
    data object NoVehicle : OdometerCheck
    data object Empty : OdometerCheck
    data class First(val vehicleName: String) : OdometerCheck
    data class Lower(val lastMiles: Int) : OdometerCheck
    data class FewerThanTracked(val delta: Int, val tracked: Double, val since: Long) : OdometerCheck
    data class LargeJump(val delta: Int, val since: Long) : OdometerCheck
    data class Ok(val delta: Int, val since: Long) : OdometerCheck
}

object OdometerReadingCheck {
    const val TRACKED_SLACK_MILES = 5.0
    const val TRACKED_SLACK_FRACTION = 0.10
    const val JUMP_MILES_PER_DAY = 1_000

    fun check(input: OdometerCheckInput): OdometerCheck {
        val vehicle = input.vehicle ?: return OdometerCheck.NoVehicle
        val last = input.last ?: return OdometerCheck.First(vehicle.name)
        val miles = input.readingMiles?.takeIf(OdometerReadingLimit::isValid) ?: return OdometerCheck.Empty
        val delta = miles - last.readingMiles
        if (delta < 0) return OdometerCheck.Lower(last.readingMiles)
        val tracked = input.gpsTotalNow?.let { now -> last.gpsTotalMiles?.let { now - it } }
            ?.takeIf { it.isFinite() && it >= 0 &&
                VehicleOdometerReading.singleVehicleSpan(input.readings, vehicle.id, last.occurredAt, input.now) }
        if (tracked != null && delta < tracked - max(TRACKED_SLACK_MILES, TRACKED_SLACK_FRACTION * tracked)) {
            return OdometerCheck.FewerThanTracked(delta, tracked, last.occurredAt)
        }
        val days = max(1.0, ceil((input.now - last.occurredAt).coerceAtLeast(0) / 86_400_000.0))
        if (delta > (tracked ?: 0.0) + JUMP_MILES_PER_DAY * days) {
            return OdometerCheck.LargeJump(delta, last.occurredAt)
        }
        return OdometerCheck.Ok(delta, last.occurredAt)
    }
}
