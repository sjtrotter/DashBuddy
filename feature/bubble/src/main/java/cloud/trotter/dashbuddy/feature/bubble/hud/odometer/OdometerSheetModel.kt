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

data class OdometerNoticeModel(val readingMiles: Int, val shownAt: Long) {
    fun visibleAt(now: Long): Boolean = now - shownAt < VISIBLE_MS

    companion object {
        const val VISIBLE_MS = 10_000L
    }
}
