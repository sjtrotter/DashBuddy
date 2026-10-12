package cloud.trotter.dashbuddy.domain.state

import kotlinx.serialization.Serializable

/**
 * A continuous period of being online on one platform.
 * Replaces the DoorDash-specific "Dash" concept.
 *
 * Earnings: null = no parsed/accumulated figure yet; never coerce to 0.0 for display (#1041).
 * A new `0.0` represents a parsed $0.00 total. A legacy snapshot/journal blob's `0.0` was
 * written by `encodeDefaults` for a NEVER-parsed session: an unknown sentinel, not a parsed value.
 * It still decodes as `0.0`, rather than being migrated to null. A crash-restore across this exact
 * upgrade can therefore make the new HUD render `$0.00` once for a dash that never parsed a total
 * (master did the same on every cold start) — accepted; the new writers never persist a `0.0`.
 */
@Serializable
data class Session(
    val sessionId: String,
    val startedAt: Long,
    val earningMode: SessionType? = null,
    val runningEarnings: Double? = null,
    val accumulatedDeliveryPay: Double? = null,
)
