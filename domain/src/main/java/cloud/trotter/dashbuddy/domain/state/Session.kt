package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

/**
 * A continuous period of being online on one platform.
 * Replaces the DoorDash-specific "Dash" concept.
 *
 * Earnings: null = no parsed/accumulated figure yet; never coerce to 0.0 for display (#1041).
 * A `0.0` is a READ value (a parsed $0.00 total). Old snapshots/journals containing `0.0`
 * still decode as `0.0`, an acceptable stale read, rather than being migrated to null.
 */
@Serializable
data class Session(
    val sessionId: String,
    val startedAt: Long,
    val earningMode: SessionType? = null,
    val runningEarnings: Double? = null,
    val accumulatedDeliveryPay: Double? = null,
)
