package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.state.Platform

internal fun lastDash() = SessionRecord(
    sessionId = "last", platform = Platform.DoorDash, startedAt = 1_000,
    endedAt = 7_201_000, reportedEarnings = 100.0, reportedDurationMillis = null,
    miles = 42.5, deliveries = 5, jobsCompleted = 5,
    offersReceived = 8, offersAccepted = 5, offersDeclined = 2, offersTimeout = 1,
)
