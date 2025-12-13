package cloud.trotter.dashbuddy.data.event

data class EventMetadata(
    /** The odometer reading in miles at the time of the event. */
    val odometer: Double? = null,

    /** Battery level (0-100). Useful for diagnosing service kills. */
    val batteryLevel: Int? = null,

    /** "WIFI", "CELLULAR", or "NONE". */
    val networkType: String? = null,

    /** The version of DashBuddy generating this event (e.g. "1.0.5"). */
    val appVersion: String? = null
)