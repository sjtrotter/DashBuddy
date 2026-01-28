package cloud.trotter.dashbuddy.ui.bubble.fragments.dashhistory.common

/**
 * A reusable data class that holds all the aggregate statistics
 * needed for the main summary card.
 */
data class SummaryStats(
    val totalDashes: Int = 0,
    val uniqueZoneCount: Int = 0,
    val totalEarnings: Double = 0.0,
    val totalHours: Double = 0.0,
    val totalMiles: Double = 0.0,
    val activeHours: Double = 0.0,
    val activeMiles: Double = 0.0
) {
    // Calculated properties for $/hr and $/mi ratios.
    // The `get()` ensures they are always up-to-date and prevents division by zero.

    val totalDollarsPerHour: Double
        get() = if (totalHours > 0) totalEarnings / totalHours else 0.0

    val totalDollarsPerMile: Double
        get() = if (totalMiles > 0) totalEarnings / totalMiles else 0.0

    val activeDollarsPerHour: Double
        get() = if (activeHours > 0) totalEarnings / activeHours else 0.0

    val activeDollarsPerMile: Double
        get() = if (activeMiles > 0) totalEarnings / activeMiles else 0.0
}