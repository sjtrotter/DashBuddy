package cloud.trotter.dashbuddy.domain.model.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One location fix.
 *
 * [accuracyMeters] and [timestampMs] carry the *quality* of the fix through to
 * [cloud.trotter.dashbuddy.domain.location.OdometerFixPolicy], which cannot judge a displacement
 * without them (#1057/#918 — the platform dropped both on the floor, so a single ~1,457 km fused fix
 * added 905 mi to the persisted odometer with nothing to reject it). Both are **nullable** — a fix
 * may genuinely lack an accuracy estimate, and every non-sensor construction site (tests, geocoding,
 * fuel-price lookups) legitimately has neither. The policy degrades to a bounded jump check when
 * either timestamp is absent.
 *
 * Neither field participates in [distanceTo], and neither is persisted — they live in memory for the
 * length of one gate decision. A latitude/longitude pair is the dasher's location (PII): it is never
 * logged, at any level (principle 7).
 */
data class Coordinates(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy radius of this fix in meters, or null when the provider reported none. */
    val accuracyMeters: Double? = null,
    /** Wall-clock instant of the fix (`Location.time`), or null when the source has no clock. */
    val timestampMs: Long? = null,
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
