package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedTime
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.dash.DashType
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.ratings.RatingsSnapshot

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
        val dashType: DashType? = null,
        val latestRatings: RatingsSnapshot? = null
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
        val isHeadingBackToZone: Boolean = false,
        val spotSaveDeadline: Long? = null  // epoch millis; populated after #137 parser lands
    ) : AppStateV2()

    data class OfferPresented(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,
        val rawOfferText: String?,
        val merchantName: String?,
        val amount: Double?,
        val currentOfferHash: String,
        val currentScreen: Screen,
        val clickInfo: Pair<Screen, ClickInfo>? = null
    ) : AppStateV2()

    data class OnPickup(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,
        val storeName: String,
        val customerNameHash: String? = null,
        val status: PickupStatus = PickupStatus.UNKNOWN,
        val arrivalConfirmed: Boolean = false,
        val orders: List<String> = emptyList(),
        val pickupDeadline: ParsedTime? = null,   // e.g. "Pick up by 17:39"
        val arrivedAt: Long? = null,              // epoch millis when ARRIVED status first set
        val itemCount: Int? = null,               // number of items to pick up
        val redCardTotal: Double? = null,         // Red Card payment total when applicable
        val odometerAtEntry: Double? = null,      // odometer snapshot when pickup task began
        val odometerAtArrival: Double? = null     // odometer snapshot when ARRIVED at store
    ) : AppStateV2()

    data class OnDelivery(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,
        val storeName: String? = null,            // carried from OnPickup
        val customerNameHash: String? = null,
        val customerAddressHash: String? = null,
        val deliveryDeadline: ParsedTime? = null, // e.g. "Deliver by 8:16 PM"
        val arrivedAt: Long? = null,              // epoch millis when ARRIVED status first detected
        val odometerAtEntry: Double? = null,      // odometer snapshot when delivery task began
        val odometerAtArrival: Double? = null     // odometer snapshot when ARRIVED at customer
    ) : AppStateV2()

    data class PostDelivery(
        override val timestamp: Long = System.currentTimeMillis(),
        override val dashId: String?,

        // Data Accumulators
        val parsedPay: ParsedPay? = null,
        val totalPay: Double = 0.0,
        val merchantNames: String = "Delivery",
        val summaryText: String = "Processing...",
        @Transient
        val latestExpandButton: UiNode? = null,
        val settleTimerStarted: Boolean = false,
        // Automation State (Closed Loop)
        val clickSent: Boolean = false,
        val clickAttempts: Int = 0
    ) : AppStateV2()

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

    val AppStateV2.isActive: Boolean
        get() = this !is IdleOffline &&
                this !is Initializing &&
                this !is PostDash
}