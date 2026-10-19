package cloud.trotter.dashbuddy.domain.model.vehicle

import kotlinx.serialization.Serializable

@Serializable
enum class FuelType(val displayName: String) {
    REGULAR("Regular (87)"),
    MIDGRADE("Midgrade (89)"),
    PREMIUM("Premium (91-93)"),
    DIESEL("Diesel"),
    ELECTRICITY("Electricity"),
}
