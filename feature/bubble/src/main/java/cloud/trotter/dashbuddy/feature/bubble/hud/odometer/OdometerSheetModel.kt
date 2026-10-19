package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import cloud.trotter.dashbuddy.domain.model.vehicle.*

enum class OdometerPage { READING, VEHICLES, ADD_VEHICLE }

enum class OdometerWriteFailure { NONE, RETRY, UNREADABLE }

enum class VehicleChange { ADDED, SWITCHED }

data class OdometerSheetModel(
    val vehicle: Vehicle?,
    val vehicles: List<Vehicle>,
    val readingMiles: Int?,
    val last: LastOdometerReading?,
    val check: OdometerCheck,
    val page: OdometerPage = OdometerPage.READING,
    val saving: Boolean = false,
    val writeFailed: OdometerWriteFailure = OdometerWriteFailure.NONE,
    val vehicleName: String = "",
)

data class OdometerNoticeModel(
    val readingMiles: Int,
    /** In-process notice timer identity; prevents StateFlow conflating identical readings. */
    val token: Long,
)
