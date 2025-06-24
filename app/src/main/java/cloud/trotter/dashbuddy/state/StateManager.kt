package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.DashBuddyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext

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

            // Determine the next state - check for dash starting or stopping first.
            val nextState = determineNextState(currentState, eventContext)

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
     * First compares the current state's activity hint to the observed screen's hint
     * to detect if a dash has started or stopped. This is a guard to correct the app's state.
     *
     * Then we delegate the event to the current handler if the state is consistent.
     *
     * @return The next AppState.
     */
    private suspend fun determineNextState(
        currentKnownState: AppState,
        context: StateContext
    ): AppState {
        val identifiedScreen = context.dasherScreen

        // --- 1. RECONCILIATION (Highest Priority) ---
        // First, ensure our active/inactive session state is correct.
        val currentHint = currentKnownState.activityHint
        val observedHint = identifiedScreen?.activityHint ?: ActivityHint.NEUTRAL

        if (currentHint != ActivityHint.NEUTRAL && observedHint != ActivityHint.NEUTRAL) {
            // CASE 1: App thinks dash is OFF, but sees a screen that implies it's ON.
            // This is the trigger for starting a dash, regardless of the specific screen.
            if (currentHint == ActivityHint.INACTIVE && observedHint == ActivityHint.ACTIVE) {
                Log.i(
                    TAG,
                    "Reconciliation: Activity detected! Current state is INACTIVE, but screen is ACTIVE. Transitioning to DashStarting."
                )
                return AppState.DASH_STARTING
            }

            // CASE 2: App thinks dash is ON, but sees a screen that implies it's OFF.
            // This is the trigger for stopping a dash.
            if (currentHint == ActivityHint.ACTIVE && observedHint == ActivityHint.INACTIVE) {
                Log.i(
                    TAG,
                    "Reconciliation: Inactivity detected! Current state is ACTIVE, but screen is INACTIVE. Transitioning to DashStopping."
                )
                return AppState.DASH_STOPPING
            }
        }

        // --- 2. CURRENT HANDLER'S LOGIC (Default Action) ---
        // If the high-level state is consistent, let the current handler process the event
        // and decide on the next state. This is where specific transitions like
        // AwaitingOffer -> OfferPresented will be handled.
        Log.d(
            TAG,
            "State is reconciled. Delegating to ${currentKnownState.handler::class.java.simpleName}."
        )
        return currentKnownState.handler.processEvent(context, currentKnownState)
    }
}
