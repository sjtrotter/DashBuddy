package cloud.trotter.dashbuddy.data.dash

/**
 * Represents the earning modes available for a dash session.
 *
 * @param displayName The text exactly as it appears in the DoorDash app.
 */
enum class DashType(val displayName: String) {
    PER_OFFER("Earn per Offer"),
    BY_TIME("Earn by Time");

    companion object {
        // A helper function to safely find an enum by its display name.
        fun fromDisplayName(name: String?): DashType? {
            return entries.find { it.displayName.equals(name, ignoreCase = true) }
        }
    }
}