package cloud.trotter.dashbuddy.state.handlers

//import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState as AppState
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen

class AwaitingOffer : StateHandler {

    private val tag = "AwaitingOfferHandler"
//    private val currentRepo = DashBuddyApplication.currentRepo

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")

        val screen = stateContext.screenInfo?.screen ?: return currentState
        // Determine next state based on screen changes
        return when {
            screen == Screen.DASH_CONTROL -> AppState.DASH_ACTIVE_ON_CONTROL
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE
            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED

            screen.isPickup -> AppState.DASH_ACTIVE_ON_PICKUP
            screen.isDelivery -> AppState.DASH_ACTIVE_ON_DELIVERY
            else -> {
//                // at this point, we have been on this screen for two separate events:
//                // -- the enterState event
//                // -- and here in processEvent (at least once)
//                // in this state, there should be NO active orders.
//                // so let's clear the queue and active order, if there are any.
//                try {
//                    if (stateContext.currentDashState?.activeOrderId != null) {
//                        Log.d(
//                            tag,
//                            "Active order found in state: ${stateContext.currentDashState.activeOrderId} - clearing."
//                        )
//                        currentRepo.setActiveOrderId(null)
//                    }
//                    if (stateContext.currentDashState?.activeOrderQueue?.isNotEmpty() == true) {
//                        Log.d(
//                            tag,
//                            "Active order queue found in state: ${stateContext.currentDashState.activeOrderQueue} - clearing."
//                        )
//                        for (order in stateContext.currentDashState.activeOrderQueue) {
//                            currentRepo.removeOrderFromQueue(order)
//                        }
//                    }
//                } catch (e: Exception) {
//                    Log.e(
//                        tag,
//                        "!!! Error clearing queue !!! - check logs!",
//                        e
//                    )
//                }
                currentState
            }
        }
    }

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d(tag, "Entering state...")
        // initialize components here
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.d(tag, "Exiting state...")
    }
}