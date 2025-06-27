package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.DashBuddyApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log

object StateManager {

    private const val TAG = "StateManager"

    private var currentState: AppState =
        AppState.UNKNOWN // Default initial state before initialization
    private lateinit var currentHandler: StateHandler

    private val currentRepo = DashBuddyApplication.currentRepo

    /** A [CoroutineScope] for handling database operations. */
    private val stateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** A [Channel] to act as a sequential event queue. */
    private val eventChannel = Channel<StateContext>(Channel.UNLIMITED)

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
        startEventProcessor()
    }

    /**
     * Public function to send an event to the processing queue.
     * This is now a fast, non-blocking "fire-and-forget" operation.
     */
    fun dispatchEvent(eventContext: StateContext) {
        stateScope.launch {
            Log.v(TAG, "Event received. Sending to event channel for processing.")
            eventChannel.send(eventContext)
        }
    }

    /**
     * NEW: Launches a single, long-running coroutine that acts as a serial worker.
     * It pulls events from the channel one by one, ensuring no race conditions.
     */
    private fun startEventProcessor() {
        Log.i(TAG, "Starting event processor worker...")
        stateScope.launch {
            for (eventContext in eventChannel) {
                // The entire logic from the old dispatchEvent is now here.
                // Because this 'for' loop processes one item at a time, we are guaranteed
                // that the previous event is finished before this one starts.
                if (!::currentHandler.isInitialized) {
                    Log.e(
                        TAG,
                        "State machine not initialized! Call initialize() first. Ignoring event: ${eventContext.eventTypeString}"
                    )
                    continue // Continue to the next event in the channel
                }

                val nextState = determineNextState(currentState, eventContext)

                if (nextState != currentState) {
                    val oldState = currentState
                    val oldHandler = currentHandler
                    val nextHandler = nextState.handler // Assume error handling as before

                    Log.i(TAG, "State Transition: $currentState -> $nextState")
                    oldHandler.exitState(eventContext, currentState, nextState)
                    currentState = nextState
                    currentHandler = nextHandler

                    // We can create a new context with the latest dash state if needed
                    val newContext = eventContext.copy(
                        currentDashState = currentRepo.getCurrentDashState(),
                    )
                    currentHandler.enterState(newContext, currentState, oldState)
                }
                // No 'else' block needed, as no state change means processing is done.
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
        val identifiedScreen = context.screenInfo?.screen

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
