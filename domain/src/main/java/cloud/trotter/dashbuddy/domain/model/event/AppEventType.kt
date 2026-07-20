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

    // --- Task abandonment (#736) ---
    TASK_UNASSIGNED, // The dasher unassigned the order mid-flow (via help). Teardown, NOT a completion:
                     // no pay/miles ever attributes, and the close-out sweep can never confirm it.

    // --- Job-close reconciliation tripwire (#810 B1) ---
    JOB_ACCEPT_MISMATCH, // A job closed with MORE accepted offers than accounted physical orders (delivered
                         // drops + unassign-marked orders) — the invisible-unassign class (seq-114): an offer
                         // died with no capturable commit surface, so no TASK_UNASSIGNED ever fired. A pure
                         // tripwire: read-model-inert (the projector's liveness `else` arm), NO state mutation,
                         // NO re-attribution — it only makes the silent seam observable. Payload = hashes +
                         // counts only (PII-safe, P7).

    // --- User corrections (#650) ---
    MANUAL_DELIVERY, // A driver-entered missed delivery (durable correction event, never destructive)
    PAY_ADJUSTMENT,  // A driver re-price of an already-recorded delivery (the original event stays)
    DELIVERY_ADJUSTMENT, // A driver multi-field edit of an already-recorded delivery (#688) — widens
                         // PAY_ADJUSTMENT (store/pay/tip/cash-tip/miles/note); the original event stays
    DELIVERY_SESSION_ASSIGN, // A driver assigns/unassigns an orphan "(No session)" delivery's session
                             // (#660 piece 2) — changes ATTRIBUTION only (never pay/net); the original event stays

    // --- System / Generic ---
    SCREEN_VIEWED, // For generic screen transitions like "Earnings Screen"
    ERROR_OCCURRED
}