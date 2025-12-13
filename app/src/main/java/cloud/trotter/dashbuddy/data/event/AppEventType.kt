package cloud.trotter.dashbuddy.data.event

enum class AppEventType {
    // --- Session Lifecycle ---
    DASH_START,
    DASH_STOP,
    ZONE_SWITCH,
//    APP_FOREGROUND,
//    APP_BACKGROUND,

    // --- Offer Phase ---
    OFFER_RECEIVED,
    OFFER_ACCEPTED,
    OFFER_DECLINED,
    OFFER_MISSED, // Timeout

    // --- Pickup Phase ---
    PICKUP_NAV_STARTED,
    PICKUP_ARRIVED,
    PICKUP_CONFIRMED, // Picked up food

    // --- Delivery Phase ---
    DELIVERY_NAV_STARTED,
    DELIVERY_ARRIVED,
    DELIVERY_COMPLETED,

    // --- System / Generic ---
    SCREEN_VIEWED, // For generic screen transitions like "Earnings Screen"
    ERROR_OCCURRED
}