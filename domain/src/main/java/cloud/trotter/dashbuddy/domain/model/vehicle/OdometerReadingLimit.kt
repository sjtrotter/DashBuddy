package cloud.trotter.dashbuddy.domain.model.vehicle

object OdometerReadingLimit {
    const val MAX_MILES = 999_999
    val DIGITS = MAX_MILES.toString().length
}
