package cloud.trotter.dashbuddy.feature.bubble.hud.odometer

import cloud.trotter.dashbuddy.domain.model.vehicle.*

enum class OdometerPage { READING, VEHICLES, ADD_VEHICLE }

/** Mirrors :app's GarageWriteFailure vocabulary; this feature cannot depend on :app.
 * The editor additionally uses GONE for stale vehicle links.
 */
enum class OdometerWriteFailure { NONE, RETRY, UNREADABLE }

data class OdometerSheetModel(
    val vehicle: Vehicle?,
    /** Live vehicles supplied by Garage.unretired. */
    val vehicles: List<Vehicle>,
    val readingMiles: Int?,
    val last: LastOdometerReading?,
    val check: OdometerCheck,
    val page: OdometerPage = OdometerPage.READING,
    val saving: Boolean = false,
    val writeFailed: OdometerWriteFailure = OdometerWriteFailure.NONE,
    val vehicleName: String = "",
    val gpsLoaded: Boolean = false,
)

data class OdometerNoticeModel(
    val readingMiles: Int,
    /** In-process notice timer identity; prevents StateFlow conflating identical readings. */
    val token: Long,
)
