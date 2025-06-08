package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen
import cloud.trotter.dashbuddy.util.NodeActionUtils
import kotlinx.coroutines.launch

class DeliveryCompleted : StateHandler {

    private val tag = this::class.simpleName ?: "DeliveryCompletedHandler"
    private var wasClickAttempted = false

    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d(tag, "Evaluating event. Current Screen: ${context.dasherScreen}")

        // After the click in enterState, the screen content will change.
        // A new TYPE_WINDOW_CONTENT_CHANGED event will trigger this processEvent method again.
        // On that subsequent event, we expect the screen to have more details (the pay breakdown).

        // This is where you would add logic to parse the *new*, expanded details.
        // After parsing those, you would transition to the next logical state.

        // For example, if the pay breakdown now includes "Customer Tip":
        if (context.screenTexts.any { it.contains("Customer Tip", ignoreCase = true) }) {
            Log.i(tag, "Pay breakdown detected. Transitioning to SESSION_ACTIVE_WAITING_FOR_OFFER.")
            // TODO: Parse the final pay breakdown details here before transitioning.
            return AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
        }

        // If the screen changes to something else entirely (e.g., user navigates away or
        // a new offer appears), handle those transitions.
        return when (context.dasherScreen) {
            Screen.OFFER_POPUP -> AppState.SESSION_ACTIVE_OFFER_PRESENTED
            Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
            // If the screen is still DELIVERY_COMPLETED_DIALOG and the breakdown hasn't appeared yet,
            // we stay in the current state, waiting for the UI to update.
            Screen.DELIVERY_COMPLETED_DIALOG -> currentState
            else -> currentState // Stay in this state by default until a known transition occurs
        }
    }

    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.i(tag, "Entering state. Screen: ${context.dasherScreen?.screenName}")
        wasClickAttempted = false // Reset flag on entering state

        // The goal is to find the dollar amount button and click it.
        // The button's text is the dollar amount itself.
        // We look for any text that starts with a '$'.
        val buttonText = context.screenTexts.find { it.trim().startsWith("$") }

        if (buttonText == null) {
            Log.w(
                tag,
                "Could not find a dollar amount button to click on the delivery completed screen."
            )
            return
        }

        Log.d(tag, "Found potential button text: '$buttonText'. Attempting to click.")

        // Use a coroutine to avoid blocking if there's any delay, although this is a quick operation.
        Manager.getScope().launch {
            // We use the root node of the current window for a full search.
            // context.sourceNode can sometimes be just a part of the screen that changed.
            // Assuming your AccessibilityService provides the root via context.sourceNode when appropriate.
            val clickSuccess =
                NodeActionUtils.findAndClickNodeByText(context.rootNode, buttonText.trim())
            if (clickSuccess) {
                Log.i(tag, "Successfully performed click on button with text: '$buttonText'")
                DashBuddyApplication.sendBubbleMessage("Pay button clicked!")
            } else {
                Log.w(tag, "Failed to perform click on button with text: '$buttonText'")
            }
            wasClickAttempted = true
        }
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.i(tag, "Exiting state to $nextState")
        wasClickAttempted = false // Reset flag
    }
}
