package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.data.event.status.PickupStatus
import cloud.trotter.dashbuddy.data.dash.DashType

sealed class AppStateV2 {
    abstract val timestamp: Long
    abstract val dashId: String?

    // --- 1. IDLE / OFFLINE ---
    data object Initializing : AppStateV2() {
        override val timestamp = System.currentTimeMillis()
        override val dashId: String? = null
    }

    // We track the Zone and DashType here so we can include them in the DASH_START event later
    data class IdleOffline(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String? = null,
        val lastKnownZone: String? = null,
        val dashType: DashType? = null
    ) : AppStateV2()

    data class PostDash(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,

        // Final Stats for this session
        val totalEarnings: Double,
        val durationMillis: Long,
        val acceptanceRateForSession: String // e.g. "3/10"
    ) : AppStateV2()

    // --- 2. ACTIVE DASH ---

    // NEW: The state when you are "Looking for orders"
    data class AwaitingOffer(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,

        // Live data from the "Looking..." screen
        val currentSessionPay: Double? = null,
        val waitTimeEstimate: String? = null,
        val isHeadingBackToZone: Boolean = false
    ) : AppStateV2()

    data class OfferPresented(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,
        val rawOfferText: String?,
        val merchantName: String?,
        val amount: Double?,
        val currentOfferHash: String
    ) : AppStateV2()

    data class OnPickup(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,
        val storeName: String,
        val customerNameHash: String? = null,
        val status: PickupStatus = PickupStatus.UNKNOWN,
        val arrivalConfirmed: Boolean = false,
        val orders: List<String> = emptyList()
    ) : AppStateV2()

    data class OnDelivery(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,
        val customerNameHash: String? = null,
        val customerAddressHash: String? = null
    ) : AppStateV2()

    data class PostDelivery(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,
        val totalPay: Double = 0.0,
        val summaryText: String = "", // Store the raw text or extracted merchant for the UI
        val phase: Phase = Phase.STABILIZING,
    ) : AppStateV2() {
        enum class Phase {
            STABILIZING,
            CLICKING,
            VERIFYING,
            RECORDED,
        }
    }

    // --- 3. TRANSIENT ---
    data class PausedOrInterrupted(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,
        val previousState: AppStateV2
    ) : AppStateV2()

    data class DashPaused(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,
        val pausedAt: Long = System.currentTimeMillis(),
        val durationMs: Long
    ) : AppStateV2()
}