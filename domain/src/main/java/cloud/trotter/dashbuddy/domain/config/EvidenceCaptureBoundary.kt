package cloud.trotter.dashbuddy.domain.config

import cloud.trotter.dashbuddy.domain.state.Platform

/** Final evidence gate, evaluated against the front package after settling. */
object EvidenceCaptureBoundary {
    enum class Verdict {
        CAPTURE,
        SKIP_DISABLED,
        SKIP_NOT_DELIVERY_APP,
        SKIP_UNREADABLE,
    }

    fun decide(
        allowedNow: Boolean,
        frontPackage: String?,
        ownPackage: String,
        enabledPlatforms: Set<Platform>,
    ): Verdict {
        if (!allowedNow) return Verdict.SKIP_DISABLED
        if (frontPackage == null) return Verdict.SKIP_UNREADABLE
        if (frontPackage == ownPackage) return Verdict.CAPTURE
        return if (Platform.fromPackage(frontPackage) in enabledPlatforms) {
            Verdict.CAPTURE
        } else {
            Verdict.SKIP_NOT_DELIVERY_APP
        }
    }
}
