package cloud.trotter.dashbuddy.state

// Assuming StateHandler interface is defined in this package or imported:
// interface StateHandler {
//     val managedState: AppState // The AppState enum this handler manages
//     fun onEnterState(context: StateContext, previousState: AppState?)
//     fun processEvent(context: StateContext): AppState // Returns the next AppState
//     fun onExitState(context: StateContext, nextState: AppState)
// }

// Import your concrete handler implementations
import cloud.trotter.dashbuddy.state.handlers.AwaitingOffer
import cloud.trotter.dashbuddy.state.handlers.DashAlongTheWay
import cloud.trotter.dashbuddy.state.handlers.Unknown
import cloud.trotter.dashbuddy.state.handlers.DasherIdleOffline
import cloud.trotter.dashbuddy.state.handlers.DasherLogin
import cloud.trotter.dashbuddy.state.handlers.OfferPresented
import cloud.trotter.dashbuddy.state.handlers.DashStarting
import cloud.trotter.dashbuddy.state.handlers.DashStopping
import cloud.trotter.dashbuddy.state.handlers.SetDashEndTime
import cloud.trotter.dashbuddy.state.handlers.Startup
import cloud.trotter.dashbuddy.state.handlers.ViewChat
import cloud.trotter.dashbuddy.state.handlers.ViewDashControl
import cloud.trotter.dashbuddy.state.handlers.ViewEarnings
import cloud.trotter.dashbuddy.state.handlers.ViewHelp
import cloud.trotter.dashbuddy.state.handlers.ViewMainMenu
import cloud.trotter.dashbuddy.state.handlers.ViewNotifications
import cloud.trotter.dashbuddy.state.handlers.ViewPromos
import cloud.trotter.dashbuddy.state.handlers.ViewRatings
import cloud.trotter.dashbuddy.state.handlers.ViewSafety
import cloud.trotter.dashbuddy.state.handlers.ViewSchedule
import cloud.trotter.dashbuddy.state.handlers.ViewTimeline

/**
 * Represents the various states the DashBuddy application perceives
 * for the monitored Dasher application.
 * Each state is associated with a concrete [StateHandler] instance
 * responsible for managing its logic and transitions.
 *
 * @property handler The concrete [StateHandler] instance for this state.
 * @property displayName A user-friendly name for the state, useful for logging.
 */
enum class App(
    val handler: StateHandler,
    val displayName: String
) {
    UNKNOWN(
        handler = Unknown(),
        displayName = "Unknown"
    ),

    // --- App Lifecycle & Pre-Dash ---
    APP_INITIALIZING(
        handler = Startup(), // Using your Startup handler
        displayName = "Dashbuddy Started!"
    ),

    //    AWAITING_DASHER_APP_FOCUS(
//        handler = PlaceHolder(AWAITING_DASHER_APP_FOCUS), // Placeholder
//        displayName = "Awaiting Dasher Focus"
//    ),
//    DASHER_APP_CLOSED_OR_BACKGROUNDED(
//        handler = PlaceHolder(DASHER_APP_CLOSED_OR_BACKGROUNDED), // Placeholder
//        displayName = "Dasher App Closed/Backgrounded"
//    ),
    DASHER_LOGIN_FLOW(
        handler = DasherLogin(), // Placeholder
        displayName = "Dasher Login Flow"
    ),
    DASHER_IDLE_OFFLINE(
        handler = DasherIdleOffline(), // Placeholder
        displayName = "Dasher Idle Offline"
    ),

    //
//    // --- Initiating a Dash ---
    DASHER_SETTING_END_TIME(
        handler = SetDashEndTime(),
        displayName = "Set Dash End Time"
    ),
    DASHER_INITIATING_DASH_SESSION(
        handler = DashStarting(),
        displayName = "Initiating Dash"
    ),

    // --- Active Dashing Session ---
    SESSION_ACTIVE_WAITING_FOR_OFFER(
        handler = AwaitingOffer(),
        displayName = "Awaiting Offer"
    ),
    SESSION_ACTIVE_DASHING_ALONG_THE_WAY(
        handler = DashAlongTheWay(), // Placeholder
        displayName = "Dash Along the Way",
    ),
    VIEWING_DASH_CONTROL(
        handler = ViewDashControl(),
        displayName = "Viewing Dash Control"
    ),
    SESSION_ACTIVE_OFFER_PRESENTED(
        handler = OfferPresented(),
        displayName = "Session Active - Offer Presented"
    ),
    VIEWING_TIMELINE(
        handler = ViewTimeline(),
        displayName = "Viewing Timeline"
    ),

    //
//    // --- Active Delivery (after offer acceptance) ---
//    DELIVERY_IN_PROGRESS_TO_STORE(
//        handler = PlaceHolder(DELIVERY_IN_PROGRESS_TO_STORE), // Placeholder
//        displayName = "Delivery - To Store"
//    ),
//    DELIVERY_IN_PROGRESS_AT_STORE(
//        handler = PlaceHolder(DELIVERY_IN_PROGRESS_AT_STORE), // Placeholder
//        displayName = "Delivery - At Store"
//    ),
//    DELIVERY_IN_PROGRESS_TO_CUSTOMER(
//        handler = PlaceHolder(DELIVERY_IN_PROGRESS_TO_CUSTOMER), // Placeholder
//        displayName = "Delivery - To Customer"
//    ),
//    DELIVERY_IN_PROGRESS_AT_CUSTOMER(
//        handler = PlaceHolder(DELIVERY_IN_PROGRESS_AT_CUSTOMER), // Placeholder
//        displayName = "Delivery - At Customer"
//    ),
//    DELIVERY_COMPLETING(
//        handler = PlaceHolder(DELIVERY_COMPLETING), // Placeholder
//        displayName = "Delivery - Completing"
//    ),
//
//    SESSION_ACTIVE_PAUSED(
//        handler = PlaceHolder(SESSION_ACTIVE_PAUSED), // Placeholder
//        displayName = "Session Active - Paused"
//    ),
//
//    // --- Ending a Dash ---
    DASHER_ENDING_DASH_SESSION(
        handler = DashStopping(),
        displayName = "Session Ending Flow"
    ),
    SESSION_ENDED_DISPLAYING_SUMMARY(
        handler = DashStopping(), // Using your SessionStop handler
        displayName = "Session Ended - Summary"
    ),
    VIEWING_RATINGS(
        handler = ViewRatings(),
        displayName = "Viewing Ratings"
    ),
    VIEWING_EARNINGS(
        handler = ViewEarnings(),
        displayName = "Viewing Earnings"
    ),
    VIEWING_MAIN_MENU(
        handler = ViewMainMenu(),
        displayName = "Opened Main Menu"
    ),
    VIEWING_SCHEDULE(
        handler = ViewSchedule(),
        displayName = "Viewing Schedule"
    ),
    VIEWING_NOTIFICATIONS(
        handler = ViewNotifications(),
        displayName = "Viewing Notifications"
    ),
    VIEWING_SAFETY(
        handler = ViewSafety(),
        displayName = "Viewing Safety"
    ),
    VIEWING_PROMOS(
        handler = ViewPromos(),
        displayName = "Viewing Promos"
    ),
    VIEWING_HELP(
        handler = ViewHelp(),
        displayName = "Viewing Help"
    ),
    VIEWING_CHATS(
        handler = ViewChat(),
        displayName = "Viewing Chats"
    ),

    ;
}