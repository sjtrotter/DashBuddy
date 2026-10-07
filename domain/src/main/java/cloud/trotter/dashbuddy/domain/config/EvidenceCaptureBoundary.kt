package cloud.trotter.dashbuddy.domain.config

import cloud.trotter.dashbuddy.domain.state.Platform

/** Final evidence gate, evaluated against the active window after the settle delay. */
object EvidenceCaptureBoundary {
    enum class Verdict {
        CAPTURE,
        SKIP_DISABLED,
        SKIP_NOT_DELIVERY_APP,
        SKIP_SENSITIVE,
        SKIP_UNREADABLE,
    }

    fun decide(
        allowedNow: Boolean,
        frontPlatform: Platform?,
        enabledPlatforms: Set<Platform>,
        sensitiveMarker: String?,
        frontReadable: Boolean,
    ): Verdict = when {
        !allowedNow -> Verdict.SKIP_DISABLED
        !frontReadable -> Verdict.SKIP_UNREADABLE
        frontPlatform == null || frontPlatform == Platform.Unknown || frontPlatform !in enabledPlatforms ->
            Verdict.SKIP_NOT_DELIVERY_APP
        sensitiveMarker != null -> Verdict.SKIP_SENSITIVE
        else -> Verdict.CAPTURE
    }
}
