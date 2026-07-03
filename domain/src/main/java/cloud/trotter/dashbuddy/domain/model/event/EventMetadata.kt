package cloud.trotter.dashbuddy.domain.model.event

import kotlinx.serialization.Serializable

/**
 * Device state stamped onto each app-event row (odometer/battery/network/version).
 *
 * Historically written by Gson (`DashBuddyApplication.createMetadata`) as a plain
 * field object, incl. the test-mode `{"test_mode": true}` shape. Now `@Serializable`
 * so the analytics projector (#314) can decode it with kotlinx: every field has a
 * default and the reader uses `ignoreUnknownKeys`, so both the Gson field shape and
 * the historical test-mode row parse cleanly; a decode failure fails to null (WARN).
 */
@Serializable
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