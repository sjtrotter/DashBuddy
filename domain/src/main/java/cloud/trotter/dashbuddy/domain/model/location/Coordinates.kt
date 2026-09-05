package cloud.trotter.dashbuddy.domain.model.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One location fix.
 *
 * [accuracyMeters], [timestampMs] and [monotonicMs] carry the *quality* of the fix through to
 * [cloud.trotter.dashbuddy.domain.location.OdometerFixPolicy], which cannot judge a displacement
 * without them (#1057/#918 — the platform dropped all of them on the floor, so a single ~1,457 km
 * fused fix added 905 mi to the persisted odometer with nothing to reject it). All three are
 * **nullable** — a fix may genuinely lack an accuracy estimate, and every non-sensor construction
 * site (tests, geocoding, fuel-price lookups) legitimately has none of them. The policy degrades to
 * a bounded jump check when it cannot compute an elapsed time.
 *
 * None of the three participates in [distanceTo], and none is persisted — they live in memory for
 * the length of one gate decision. A latitude/longitude pair is the dasher's location (PII): it is
 * never logged, at any level (principle 7).
 */
data class Coordinates(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy radius of this fix in meters, or null when the provider reported none. */
    val accuracyMeters: Double? = null,
    /**
     * Wall-clock instant of the fix (`Location.time`), or null when the source has no clock.
     *
     * **Display and logging only.** It is a *settable* clock: an NTP correction can step it
     * backwards mid-drive, which is exactly why elapsed-time math prefers [monotonicMs] (#1057
     * round 2 — a 60 s step-back used to reject every fix for ~110 s, losing that mileage).
     */
    val timestampMs: Long? = null,
    /**
     * Monotonic instant of the fix (`Location.elapsedRealtimeNanos`, milliseconds since boot), or
     * null when the source has no monotonic clock (tests, synthetic sources).
     *
     * **This is the ordering key.** Android's own guidance is not to order or compare fixes by
     * `Location.time`; `elapsedRealtime` cannot be stepped by a clock correction, so a difference
     * between two of these values is real elapsed time.
     */
    val monotonicMs: Long? = null,
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

        // Floating-point rounding can push the intermediate a hair past 1.0 on near-antipodal
        // points (`1.0000000000000004`), and `asin(sqrt(a))` of that is NaN — which used to flow
        // straight into the odometer's persisted total. Clamping keeps the formula total (#1057).
        val a = (
            sin(dLat / 2).pow(2) +
                sin(dLon / 2).pow(2) * cos(originLat) * cos(destinationLat)
            ).coerceIn(0.0, 1.0)
        val c = 2 * asin(sqrt(a))

        return earthRadiusMeters * c
    }
}
