package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

/**
 * A continuous period of being online on one platform.
 * Replaces the DoorDash-specific "Dash" concept.
 */
@Serializable
data class Session(
    val sessionId: String,
    val startedAt: Long,
    val earningMode: SessionType? = null,
    val runningEarnings: Double = 0.0,
    val runningMiles: Double = 0.0,
    val accumulatedDeliveryPay: Double = 0.0,
)
