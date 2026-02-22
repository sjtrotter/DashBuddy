package cloud.trotter.dashbuddy.state.model

enum class TimeoutType {
    // Global Safety
    DASH_PAUSED_SAFETY,

    // Post Delivery Reducer
    RETRY_CLICK_TIMEOUT,
    SETTLE_UI,

    // Offer Reducer (Decline Flow)
    DECLINE_POPUP_WAIT,

    // Screenshot Delay
    SCREENSHOT_WAIT,
}