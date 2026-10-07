package cloud.trotter.dashbuddy.domain.config

import cloud.trotter.dashbuddy.domain.state.Platform

/** Final evidence gate, evaluated against every window on the screenshot display after settling. */
object EvidenceCaptureBoundary {
    enum class Kind { APPLICATION, SYSTEM, INPUT_METHOD, OVERLAY, OTHER }

    data class WindowSnapshot(
        val displayId: Int,
        val platform: Platform,
        val isOwnApp: Boolean,
        val kind: Kind,
        val complete: Boolean,
        val sensitiveMarker: String?,
    )

    enum class Verdict {
        CAPTURE,
        SKIP_DISABLED,
        SKIP_NOT_DELIVERY_APP,
        SKIP_SENSITIVE,
        SKIP_UNREADABLE,
    }

    fun decide(
        allowedNow: Boolean,
        enabledPlatforms: Set<Platform>,
        windows: List<WindowSnapshot>,
        targetDisplayId: Int,
    ): Verdict {
        if (!allowedNow) return Verdict.SKIP_DISABLED
        val considered = windows.filter {
            it.displayId == targetDisplayId && !it.isOwnApp && it.kind != Kind.SYSTEM
        }
        return when {
            considered.isEmpty() -> Verdict.SKIP_UNREADABLE
            considered.any { it.kind == Kind.INPUT_METHOD } -> Verdict.SKIP_NOT_DELIVERY_APP
            considered.any { it.platform == Platform.Unknown || it.platform !in enabledPlatforms } ->
                Verdict.SKIP_NOT_DELIVERY_APP
            considered.any { !it.complete } -> Verdict.SKIP_UNREADABLE
            considered.any { it.sensitiveMarker != null } -> Verdict.SKIP_SENSITIVE
            considered.none { it.platform in enabledPlatforms } -> Verdict.SKIP_NOT_DELIVERY_APP
            else -> Verdict.CAPTURE
        }
    }
}
