package cloud.trotter.dashbuddy.services.accessibility.click

/**
 * An enumeration of the specific, actionable buttons we care about.
 */
enum class ClickType {
    // --- Offer Handling ---
    ACCEPT_OFFER,
    DECLINE_OFFER, // This is the final decline confirmation
    DECLINE_OFFER_INITIAL, // The first "Decline" click on the offer screen
    VIEW_PAY_DETAILS,
    DECLINE_ORDER,

    // --- Dash Lifecycle ---
    START_DASH,
    SELECT_DASH_END_TIME,
    OPEN_DASH_CONTROLS,
    RETURN_TO_DASH,
    PAUSE_DASH,
    RESUME_DASH,
    CONFIRM_END_DASH,
    CANCEL_END_DASH,
    CONTINUE_DASHING, // After earnings are shown

    // --- Main Navigation & Menus ---
    OPEN_MAIN_MENU,
    VIEW_PROMOS,
    VIEW_EARNINGS,
    VIEW_TIMELINE,
    NAVIGATE_UP,

    // --- Pickup / At Store Flow ---
    ARRIVED_AT_STORE,
    START_SHOPPING,
    SCAN_ITEM_BARCODE,
    PROCEED_TO_CHECKOUT,
    CONFIRM_PAYMENT,
    TAKE_RECEIPT_PHOTO,
    SKIP_RECEIPT_PHOTO,
    CONFIRM_PICKUP,
    TELL_US_WHATS_CAUSING_WAIT,
    SUBMIT_WAIT_REASON,

    // --- Delivery / Drop-off Flow ---
    OPEN_DIRECTIONS,
    MESSAGE_CUSTOMER,
    SEND_INTRO_MESSAGE,
    COMPLETE_DELIVERY_STEPS,
    CANNOT_HAND_TO_CUSTOMER,
    TAKE_PHOTO, // Generic for drop-off or receipt
    CAPTURE_IMAGE, // The shutter button
    CONFIRM_DELIVERY,
    VERIFY_RECIPIENT_ID,
    CONFIRM_RECEIVED_ORDER_SIGNATURE,
    AGREE_AND_CONTINUE,

    // --- Generic / Common Actions ---
    DONE,
    NEXT,
    CONTINUE,
    CONFIRM,
    GOT_IT,
    GO_BACK,
    NEED_HELP

}