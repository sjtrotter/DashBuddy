package cloud.trotter.dashbuddy.domain.model.vehicle

/**
 * Represents a single selectable vehicle trim option returned by a vehicle data provider.
 * Replaces the provider-specific DTO (e.g. EPA's MenuItem) so consumers never
 * need to import from :core:network.
 *
 * @param id     The provider-specific identifier used to fetch further details (e.g. MPG lookup).
 * @param displayName The human-readable label shown in the UI.
 */
data class VehicleOption(
    val id: String,
    val displayName: String
)
