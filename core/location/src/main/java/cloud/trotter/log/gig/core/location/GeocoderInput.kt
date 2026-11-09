package cloud.trotter.log.gig.core.location

import kotlin.math.round

/**
 * What Android's `Geocoder` is given for the gas-price region lookup: the fix rounded to one decimal place of a
 * degree (about 11 km of latitude), never the exact fix. Only the state comes back into use (`stateName`), and a
 * point that coarse still resolves it. PRIVACY.md §2.4 states this.
 */
internal object GeocoderInput {
    const val DECIMALS = 1
    private const val SCALE = 10.0

    fun degrees(value: Double): Double = round(value * SCALE) / SCALE
}
