package cloud.trotter.dashbuddy.domain.model.event

enum class AppEventType {
    // --- Session Lifecycle ---
    DASH_START,
    DASH_PAUSED,
    DASH_STOP,
    ZONE_SWITCH,
    NOTIFICATION_RECEIVED,

    // --- Offer Phase ---
    OFFER_RECEIVED,
    OFFER_ACCEPTED,
    OFFER_DECLINED,
    OFFER_TIMEOUT, // Timeout

    // --- Pickup Phase ---
    PICKUP_NAV_STARTED,
    PICKUP_ARRIVED,
    PICKUP_CONFIRMED, // Picked up food

    // --- Delivery Phase ---
    DELIVERY_NAV_STARTED,
    DELIVERY_ARRIVED,
    DELIVERY_CONFIRMED, // Dasher finished the drop-off (handoff or POD photo done); analogue of PICKUP_CONFIRMED
    DELIVERY_COMPLETED, // PostTask exit (receipt dismissed); carries pay breakdown

    // --- User corrections (#650) ---
    MANUAL_DELIVERY, // A driver-entered missed delivery (durable correction event, never destructive)
    PAY_ADJUSTMENT,  // A driver re-price of an already-recorded delivery (the original event stays)
    DELIVERY_ADJUSTMENT, // A driver multi-field edit of an already-recorded delivery (#688) — widens
                         // PAY_ADJUSTMENT (store/pay/tip/cash-tip/miles/note); the original event stays

    // --- System / Generic ---
    SCREEN_VIEWED, // For generic screen transitions like "Earnings Screen"
    ERROR_OCCURRED
}