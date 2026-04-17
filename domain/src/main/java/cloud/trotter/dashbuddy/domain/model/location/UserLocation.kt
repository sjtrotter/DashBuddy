package cloud.trotter.dashbuddy.domain.model.location

/**
 * A pure Kotlin representation of the user's physical location.
 * Contains everything a 3rd party API might possibly need.
 */
data class UserLocation(
    val coordinates: Coordinates,
    val city: String? = null,
    val stateName: String? = null,
    val zipCode: String? = null,
)