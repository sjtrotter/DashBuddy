package cloud.trotter.log.gig.domain.model.vehicle

object OdometerReadingLimit {
    const val MAX_MILES = 999_999
    val DIGITS = MAX_MILES.toString().length

    fun isValid(miles: Int): Boolean = miles in 1..MAX_MILES
}
