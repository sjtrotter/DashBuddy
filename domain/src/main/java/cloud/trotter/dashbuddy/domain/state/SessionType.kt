package cloud.trotter.dashbuddy.domain.state

/**
 * The earning mode for a session. Replaces the DoorDash-specific `DashType`.
 */
enum class SessionType(val displayName: String) {
    PerOffer("Earn per Offer"),
    ByTime("Earn by Time"),
    ;

    companion object {
        fun fromDisplayName(name: String?): SessionType? =
            entries.find { it.displayName.equals(name, ignoreCase = true) }
    }
}
