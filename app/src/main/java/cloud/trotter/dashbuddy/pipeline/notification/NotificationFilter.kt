package cloud.trotter.dashbuddy.pipeline.notification

import javax.inject.Inject

class NotificationFilter @Inject constructor() {

    // Centralized list of keywords we care about.
    // This makes it easy to add "Incentive" or "Bonus" later without touching logic.
    private val relevantKeywords = listOf(
        "New Order",
        "Tip",
        "Review",
        "Dasher",
        "Complete",
        "Paid",
        "Incentive",
        "Challenge"
    )

    fun isRelevant(info: NotificationInfo): Boolean {
        // 1. Fast Package Check (Redundant safety, mostly handled by Source)
        if (info.packageName != "com.doordash.driverapp") return false

        // 2. Content Check
        // We check the full text representation to catch keywords in title OR body
        val text = info.toFullString()
        return relevantKeywords.any { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
    }
}