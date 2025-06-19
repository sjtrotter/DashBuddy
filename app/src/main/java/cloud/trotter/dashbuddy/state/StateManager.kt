package cloud.trotter.dashbuddy.state

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

    /** A channel that acts as a sequential queue for database jobs. */
    private val dbWorkChannel = Channel<suspend CoroutineScope.() -> Unit>(Channel.UNLIMITED)

    /** Add database work to the queue. */
    fun enqueueDbWork(work: suspend CoroutineScope.() -> Unit) {
        stateScope.launch {
            dbWorkChannel.send(work)
        }
    }

    /**
     * Launches a single, long-running coroutine that acts as a serial worker.
     * It pulls jobs from the channel one by one and executes them to completion.
     */
    private fun startDbWorker() {
        Log.i(TAG, "Starting database worker...")
        stateScope.launch(Dispatchers.IO) {
            for (work in dbWorkChannel) {
                try {
                    Log.d(TAG, "Executing next job in DB queue...")
                    work()
                    Log.d(TAG, "DB job completed.")
                } catch (e: Exception) {
                    Log.e(TAG, "!!! CRITICAL error in a DB worker job !!!", e)
                }
            }
        }
    }

    /** A [CoroutineScope] for handling database operations. */
    private val stateScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getScope(): CoroutineScope = stateScope

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
        currentHandler.enterState(
            initialContext,
            currentState,
            null
        )

        startDbWorker()
    }

    /**
     * Call this from your AccessibilityEventHandler when a new, debounced event occurs.
     * @param eventContext The context derived from the AccessibilityEvent.
     */
    fun dispatchEvent(eventContext: StateContext) {
        if (!::currentHandler.isInitialized) {
            Log.e(
                TAG,
                "State machine not initialized! Call initialize() first. Ignoring event: ${eventContext.eventTypeString}"
            )
            // Attempt to initialize if not already, though this might indicate a logic error in app startup.
            // initialize(eventContext.androidAppContext) // Consider if this is safe or desirable
            return
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
                return // Abort transition if next handler is problematic
            }

            Log.i(TAG, "State Transition: $currentState -> $nextState")

            oldHandler.exitState(eventContext, currentState, nextState)

            currentState = nextState
            currentHandler = nextHandler

            currentHandler.enterState(eventContext, currentState, oldState)
        } else {
            // The event was processed by the current handler, but the state remains the same.
            // The currentHandler might have updated its internal data or performed actions.
            Log.d(
                TAG,
                "Event processed by ${currentHandler::class.java.simpleName}. State remains: $currentState"
            )
        }
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

//        if (context.sourceNodeTexts.contains("Open navigation drawer") && context.sourceNodeTexts.size == 1) {
//            return AppState.VIEWING_MAIN_MENU
//        }

//        if (identifiedScreen == DasherScreen.RATINGS_VIEW && currentKnownState != AppState.VIEWING_RATINGS) {
//            return AppState.VIEWING_RATINGS
//        }
//        if (identifiedScreen == DasherScreen.OFFER_POPUP && currentKnownState != AppState.SESSION_ACTIVE_OFFER_PRESENTED) {
//            return AppState.SESSION_ACTIVE_OFFER_PRESENTED
//        }

        // ... other high-priority screen-to-state mappings ...

        // Then, consider transitions based on the current AppState
        return when (currentKnownState) {
            AppState.APP_INITIALIZING, AppState.DASHER_LOGIN_FLOW -> { //, AppState.AWAITING_DASHER_APP_FOCUS -> {
                if (identifiedScreen == DasherScreen.MAIN_MAP_IDLE) AppState.DASHER_IDLE_OFFLINE
//                else if (identifiedScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER || identifiedScreen == Screen.ON_DASH_ALONG_THE_WAY) AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
                else currentKnownState
            }


//            AppState.DASHER_IDLE_OFFLINE -> {
//                if (identifiedScreen == DasherScreen.SELECT_DASH_END_TIME) AppState.DASHER_INITIATING_DASH_SESSION
////                else if (identifiedScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER || identifiedScreen == Screen.ON_DASH_ALONG_THE_WAY) AppState.SESSION_ACTIVE_WAITING_FOR_OFFER // e.g., auto-started dash
//                else currentKnownState
//            }
//            AppState.DASHER_INITIATING_DASH_SESSION -> {
//                if (identifiedScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER || identifiedScreen == Screen.ON_DASH_ALONG_THE_WAY) AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
//                else if (identifiedScreen == Screen.MAIN_MAP_IDLE) AppState.DASHER_IDLE_OFFLINE // Failed to start?
//                else currentKnownState
//            }
//            AppState.SESSION_ACTIVE_WAITING_FOR_OFFER -> {
//                // Offer popups are handled globally above, so they'd take precedence
//                if (identifiedScreen == Screen.DASH_PAUSED_SCREEN) AppState.SESSION_ACTIVE_PAUSED
//                else if (identifiedScreen == Screen.MAIN_MAP_IDLE || identifiedScreen == Screen.DASH_SUMMARY_SCREEN) AppState.DASHER_IDLE_OFFLINE // Dash ended somehow
//                else currentKnownState
//            }
//            AppState.SESSION_ACTIVE_OFFER_PRESENTED -> {
//                // Logic here depends on how you detect accept/decline.
//                // If accept leads to DELIVERY_NAVIGATION_TO_STORE screen:
//                if (identifiedScreen == Screen.DELIVERY_NAVIGATION_TO_STORE) AppState.DELIVERY_IN_PROGRESS_TO_STORE
//                // If decline leads back to waiting or idle map:
//                else if (identifiedScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER) AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
//                else if (identifiedScreen == Screen.MAIN_MAP_IDLE) AppState.DASHER_IDLE_OFFLINE
//                else currentKnownState
//            }
            // ... handle all other states and their common transitions based on identifiedScreen ...
            else -> currentKnownState // Default to staying in the current state
        }
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
