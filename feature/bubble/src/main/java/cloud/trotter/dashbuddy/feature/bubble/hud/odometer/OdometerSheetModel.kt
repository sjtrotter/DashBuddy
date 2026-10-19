package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import cloud.trotter.dashbuddy.domain.model.vehicle.*

enum class OdometerPage { READING, VEHICLES, ADD_VEHICLE }

data class OdometerSheetModel(
    val vehicle: Vehicle?,
    val vehicles: List<Vehicle>,
    val readingMiles: Int?,
    val last: LastOdometerReading?,
    val check: OdometerCheck,
    val page: OdometerPage = OdometerPage.READING,
    val saving: Boolean = false,
    val writeFailed: Boolean = false,
)

data class OdometerNoticeModel(val readingMiles: Int, val savedAt: Long)
