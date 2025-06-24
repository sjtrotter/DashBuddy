package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.DashBuddyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log // Your Logger alias
// AppState enum (from app_state_kt_with_user_handlers artifact) is expected to be in this package or imported.
// StateHandler interface is expected to be defined and imported.
// StateContext data class is expected to be defined and imported.
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.screens.Screen as DasherScreen

object StateManager {

    private const val TAG = "StateManager"

    private var currentState: AppState =
        AppState.UNKNOWN // Default initial state before initialization
    private lateinit var currentHandler: StateHandler

    private val currentRepo = DashBuddyApplication.currentRepo

    /** A [CoroutineScope] for handling database operations. */
    private val stateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Initializes the StateManager.
     * Call this once, e.g., from Application.onCreate() or when your AccessibilityService is connected.
     * @param initialContext The application context.
     * @param initialAppState The desired initial state for the machine. Defaults to APP_INITIALIZING.
     */
    fun initialize(
        initialContext: StateContext,
        initialAppState: AppState = AppState.APP_INITIALIZING
    ) {
        Log.i(TAG, "Initializing State Manager...")

        // Ensure the initial state has a handler defined in the enum
        try {
            currentHandler = initialAppState.handler
            currentState = initialAppState
        } catch (e: Exception) {
            Log.e(
                TAG,
                "!!! Error getting handler for initial state $initialAppState. Defaulting to UNKNOWN. Check AppState enum. Error: ${e.message} !!!"
            )
            // Fallback to UNKNOWN state if initial handler is problematic
            currentState = AppState.UNKNOWN
            currentHandler =
                AppState.UNKNOWN.handler // Assumes UNKNOWN state and handler are always valid
        }

        Log.i(
            TAG,
            "Transitioning to initial state: $currentState (${currentHandler::class.java.simpleName})"
        )
        stateScope.launch {
            currentHandler.enterState(
                initialContext,
                currentState,
                null
            )
        }
    }

    /**
     * Call this from your AccessibilityEventHandler when a new, debounced event occurs.
     * @param eventContext The context derived from the AccessibilityEvent.
     */
    fun dispatchEvent(eventContext: StateContext) {
        stateScope.launch {
            if (!::currentHandler.isInitialized) {
                Log.e(
                    TAG,
                    "State machine not initialized! Call initialize() first. Ignoring event: ${eventContext.eventTypeString}"
                )
                // Attempt to initialize if not already, though this might indicate a logic error in app startup.
                // initialize(eventContext.androidAppContext) // Consider if this is safe or desirable
                return@launch
            }

            Log.d(
                TAG,
                "Dispatching event to current state: $currentState (${currentHandler::class.java.simpleName}). EventType: ${eventContext.eventTypeString}"
            )

            // Run processEvent in currentHandler, store what it thinks the next state should be
            val processResult = currentHandler.processEvent(eventContext, currentState)

            // Determine the next state based on the processResult
            val nextState = determineNextState(currentState, eventContext, processResult)

            if (nextState != currentState) {
                val oldState = currentState
                val oldHandler = currentHandler

                // Get the new handler from the nextState
                val nextHandler: StateHandler
                try {
                    nextHandler = nextState.handler
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "!!! Error getting handler for next state $nextState. Transition aborted. Staying in $currentState. Error: ${e.message} !!!"
                    )
                    return@launch // Abort transition if next handler is problematic
                }

                Log.i(TAG, "State Transition: $currentState -> $nextState")

                oldHandler.exitState(eventContext, currentState, nextState)

                currentState = nextState
                currentHandler = nextHandler
                val newContext = eventContext.copy(
                    currentDashState = currentRepo.getCurrentDashState(),
                )

                currentHandler.enterState(newContext, currentState, oldState)
            } else {
                // The event was processed by the current handler, but the state remains the same.
                // The currentHandler might have updated its internal data or performed actions.
                Log.d(
                    TAG,
                    "Event processed by ${currentHandler::class.java.simpleName}. State remains: $currentState"
                )
            }
        }
    }

    /**
     * Compares the current state's activity hint to the observed screen's hint
     * to detect if a dash has started or stopped unexpectedly.
     * This acts as a high-level guard to correct the app's state.
     *
     * @return A new AppState if a transition is needed, otherwise null.
     */
    private fun reconcileDashState(currentKnownState: AppState, context: StateContext): AppState? {
        // TODO: wire this in.
        val newScreen = context.dasherScreen ?: return null

        val currentHint = currentKnownState.activityHint
        val observedHint = newScreen.activityHint

        // If either hint is NEUTRAL, we don't have enough information to reconcile.
        // Let the specific state handler decide what to do.
        if (currentHint == ActivityHint.NEUTRAL || observedHint == ActivityHint.NEUTRAL) {
            return null
        }

        // CASE 1: App thinks dash is INACTIVE, but sees a screen that implies it's ACTIVE.
        if (currentHint == ActivityHint.INACTIVE && observedHint == ActivityHint.ACTIVE) {
            Log.i(
                TAG,
                "Reconciliation: Activity detected! Current state is INACTIVE, but screen is ACTIVE. Transitioning to DashStarting."
            )
            return AppState.DASHER_INITIATING_DASH_SESSION
        }

        // CASE 2: App thinks dash is ACTIVE, but sees a screen that implies it's INACTIVE.
        // This handles all end-of-dash scenarios (manual, auto, force-stop recovery).
        if (currentHint == ActivityHint.ACTIVE && observedHint == ActivityHint.INACTIVE) {
            Log.i(
                TAG,
                "Reconciliation: Inactivity detected! Current state is ACTIVE, but screen is INACTIVE. Transitioning to DashStopping."
            )
            return AppState.DASHER_ENDING_DASH_SESSION
        }

        // No conflicting active/inactive state change detected.
        // Return null to allow the current state's handler to process the event.
        return null
    }


    private fun determineNextState(
        currentKnownState: AppState,
        context: StateContext,
        processResult: AppState
    ): AppState {
        // If the handler determined a subsequent state, use that
        if (processResult != currentKnownState) {
            return processResult
        }
        val identifiedScreen = context.dasherScreen
        Log.d(
            TAG,
            "Determining next state. Current AppState: $currentKnownState, Identified Screen: $identifiedScreen"
        )

        // Handle global/forced transitions first
//        if (identifiedScreen == DasherScreen.APP_STARTING_OR_LOADING &&
//            currentKnownState != AppState.DASHER_LOGIN_FLOW
//        ) {
//            return AppState.DASHER_LOGIN_FLOW
//        }

        // PRIORITY - Offer Popped Up on Screen
        if (identifiedScreen == DasherScreen.OFFER_POPUP &&
            currentKnownState != AppState.SESSION_ACTIVE_OFFER_PRESENTED
        ) {
            return AppState.SESSION_ACTIVE_OFFER_PRESENTED
        }

        // PRIORITY - Delivery Completed
        if (identifiedScreen == DasherScreen.DELIVERY_COMPLETED_DIALOG &&
            currentKnownState != AppState.DELIVERY_COMPLETED
        ) {
            return AppState.DELIVERY_COMPLETED
        }

        // Screens that can be back-buttoned into (or gestured) -- needed?
        if (identifiedScreen == DasherScreen.MAIN_MAP_IDLE &&
            currentKnownState != AppState.DASHER_IDLE_OFFLINE
        ) {
            return AppState.DASHER_IDLE_OFFLINE
        }

        return currentKnownState // Default to staying in the current state
    }

    /**
     * Gets the current high-level application state.
     * @return The current [AppState].
     */
    fun getCurrentAppState(): AppState = currentState

    /**
     * Gets the handler for the current state.
     * Useful if you need to directly interact with the current handler's specific methods (though rare).
     * @return The current [StateHandler], or null if not initialized.
     */
    fun getCurrentStateHandler(): StateHandler? {
        return if (::currentHandler.isInitialized) currentHandler else null
    }
}
