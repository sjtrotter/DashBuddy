package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import cloud.trotter.dashbuddy.domain.model.vehicle.*

enum class OdometerPage { READING, VEHICLES, ADD_VEHICLE }

enum class OdometerWriteFailure { NONE, RETRY, UNREADABLE }

data class OdometerSheetModel(
    val vehicle: Vehicle?,
    val vehicles: List<Vehicle>,
    val readingMiles: Int?,
    val last: LastOdometerReading?,
    val check: OdometerCheck,
    val page: OdometerPage = OdometerPage.READING,
    val saving: Boolean = false,
    val writeFailed: OdometerWriteFailure = OdometerWriteFailure.NONE,
)

data class OdometerNoticeModel(
    val readingMiles: Int,
    /** Unique save identity for the sheet's one-time close and notice timer. */
    val token: Long,
)
