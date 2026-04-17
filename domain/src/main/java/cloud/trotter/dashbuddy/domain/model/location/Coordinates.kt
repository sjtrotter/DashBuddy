package cloud.trotter.dashbuddy.domain.model.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Coordinates(
    val latitude: Double,
    val longitude: Double
) {
    /**
     * Calculates the great-circle distance in meters to another set of coordinates
     * using the Haversine formula.
     */
    fun distanceTo(other: Coordinates): Double {
        val earthRadiusMeters = 6371000.0 // Approx radius of the Earth in meters

        val dLat = Math.toRadians(other.latitude - this.latitude)
        val dLon = Math.toRadians(other.longitude - this.longitude)

        val originLat = Math.toRadians(this.latitude)
        val destinationLat = Math.toRadians(other.latitude)

        val a = sin(dLat / 2).pow(2) +
                sin(dLon / 2).pow(2) * cos(originLat) * cos(destinationLat)
        val c = 2 * asin(sqrt(a))

        return earthRadiusMeters * c
    }
}