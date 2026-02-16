package cloud.trotter.dashbuddy.data.event

enum class AppEventType {
    // --- Session Lifecycle ---
    DASH_START,
    DASH_PAUSED,
    DASH_STOP,
    ZONE_SWITCH,
    NOTIFICATION_RECEIVED,
//    APP_FOREGROUND,
//    APP_BACKGROUND,

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
    DELIVERY_COMPLETED,

    // --- System / Generic ---
    SCREEN_VIEWED, // For generic screen transitions like "Earnings Screen"
    ERROR_OCCURRED
}