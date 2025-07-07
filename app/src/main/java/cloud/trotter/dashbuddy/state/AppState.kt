package cloud.trotter.dashbuddy.state

// Assuming StateHandler interface is defined in this package or imported:
// interface StateHandler {
//     val managedState: AppState // The AppState enum this handler manages
//     fun onEnterState(context: StateContext, previousState: AppState?)
//     fun processEvent(context: StateContext): AppState // Returns the next AppState
//     fun onExitState(context: StateContext, nextState: AppState)
// }

// import the DashBuddyApplication database repos to inject into the handlers
// Import your concrete handler implementations
import cloud.trotter.dashbuddy.state.handlers.AwaitingOffer
import cloud.trotter.dashbuddy.state.handlers.Unknown
import cloud.trotter.dashbuddy.state.handlers.DasherIdleOffline
import cloud.trotter.dashbuddy.state.handlers.OfferPresented
import cloud.trotter.dashbuddy.state.handlers.DashStarting
import cloud.trotter.dashbuddy.state.handlers.DashStopping
import cloud.trotter.dashbuddy.state.handlers.DeliveryCompleted
import cloud.trotter.dashbuddy.state.handlers.OnDelivery
import cloud.trotter.dashbuddy.state.handlers.OnNavigation
import cloud.trotter.dashbuddy.state.handlers.OnPickup
import cloud.trotter.dashbuddy.state.handlers.OrderPickedUp
import cloud.trotter.dashbuddy.state.handlers.Startup
import cloud.trotter.dashbuddy.state.handlers.ViewDashControl
import cloud.trotter.dashbuddy.state.handlers.ViewEarnings
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
enum class AppState(
    val handler: StateHandler,
    val displayName: String,
    val activityHint: ActivityHint = ActivityHint.NEUTRAL
) {
    UNKNOWN(
        handler = Unknown(),
        displayName = "Unknown"
    ),

    // --- Initializing ---
    APP_INITIALIZING(
        handler = Startup(),
        displayName = "Dashbuddy Started!"
    ),

    // --- Offline States ---
    DASH_IDLE_OFFLINE(
        handler = DasherIdleOffline(), // Placeholder
        displayName = "Dasher Idle Offline",
        activityHint = ActivityHint.INACTIVE
    ),
    DASH_IDLE_ON_SUMMARY(
        handler = DashStopping(), // Using your SessionStop handler
        displayName = "Session Ended - Summary",
        activityHint = ActivityHint.INACTIVE
    ),
    DASH_IDLE_ON_EARNINGS(
        handler = ViewEarnings(),
        displayName = "Viewing Earnings",
        activityHint = ActivityHint.INACTIVE
    ),

    // --- Initiating a Dash ---
    DASH_STARTING(
        handler = DashStarting(),
        displayName = "Starting Dash"
    ),

    // --- Active Dashing Session ---
    DASH_ACTIVE_AWAITING_OFFER(
        handler = AwaitingOffer(),
        displayName = "Awaiting Offer",
        activityHint = ActivityHint.ACTIVE
    ),
    DASH_ACTIVE_OFFER_PRESENTED(
        handler = OfferPresented(),
        displayName = "Offer Presented",
        activityHint = ActivityHint.ACTIVE
    ),
    DASH_ACTIVE_ON_CONTROL(
        handler = ViewDashControl(),
        displayName = "Dash Control",
        activityHint = ActivityHint.ACTIVE
    ),
    DASH_ACTIVE_ON_NAVIGATION(
        handler = OnNavigation(),
        displayName = "Dash Active - On Navigation",
        activityHint = ActivityHint.ACTIVE
    ),
    DASH_ACTIVE_ON_PICKUP(
        handler = OnPickup(),
        displayName = "Dash Active - On Pickup",
        activityHint = ActivityHint.ACTIVE
    ),
    DASH_ACTIVE_PICKED_UP(
        handler = OrderPickedUp(),
        displayName = "Dash Active - Order Picked Up",
        activityHint = ActivityHint.ACTIVE
    ),
    DASH_ACTIVE_ON_DELIVERY(
        handler = OnDelivery(),
        displayName = "Dash Active - On Delivery",
        activityHint = ActivityHint.ACTIVE
    ),
    DASH_ACTIVE_ON_TIMELINE(
        handler = ViewTimeline(),
        displayName = "Dash Timeline",
        activityHint = ActivityHint.ACTIVE
    ),
    DASH_ACTIVE_DELIVERY_COMPLETED(
        handler = DeliveryCompleted(), // Placeholder
        displayName = "Delivery Completed",
        activityHint = ActivityHint.ACTIVE
    ),

    // --- Ending a Dash ---
    DASH_STOPPING(
        handler = DashStopping(),
        displayName = "Stopping Dash"
    ),

    ;
}