package cloud.trotter.dashbuddy.state.model

enum class TimeoutType {
    // Global Safety
    DASH_PAUSED_SAFETY,

    // Expanding Reducer
    EXPAND_STABILITY,
    EXPAND_CLICK_FAIL,

    // Post Delivery Reducer
    VERIFY_PAY,

    // Offer Reducer (Decline Flow)
    DECLINE_POPUP_WAIT,

    // Screenshot Delay
    SCREENSHOT_WAIT,
}