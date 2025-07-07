package cloud.trotter.dashbuddy.state.handlers

import android.view.accessibility.AccessibilityEvent
import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.order.OrderStatus
import cloud.trotter.dashbuddy.data.store.StoreEntity
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.util.OrderMatcher
import cloud.trotter.dashbuddy.util.UtilityFunctions

/**
 * Manages the entire pickup phase, from offer acceptance to confirming the last pickup.
 * It acts as a dispatcher, reacting to whatever screen the Dasher app shows.
 */
class OnPickup : StateHandler {

    private val tag = this::class.simpleName ?: "OnPickupHandler"
    private val currentRepo = DashBuddyApplication.currentRepo
    private val orderRepo = DashBuddyApplication.orderRepo
    private val storeRepo = DashBuddyApplication.storeRepo
    private var isClicked: Boolean = false
    private var screenClicked: Screen? = null

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        isClicked = false
        screenClicked = null
        Log.i(tag, "--- Entering $tag ---")
        // Determine the active order and set it as the focused order
        val activeOrder = OrderMatcher.matchOrder(stateContext)
        if (activeOrder != null) {
            currentRepo.updateActiveOrderFocus(activeOrder)
            Log.d(tag, "Focused order: $activeOrder")
        } else {
            Log.d(tag, "No active order found.")
            return
        }
        updateFromContext(stateContext, currentState)
    }

    override suspend fun processEvent(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {

        val screen = stateContext.screenInfo?.screen ?: return currentState

        // set click, if click detected.
        // (for now, just going to set click and log the screen that was clicked)
        // (will worry about resetting click later on with more data)
        if (stateContext.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            isClicked = true
            screenClicked = screen
            Log.d(tag, "Screen clicked: $screen")
        }

        // Determine next state based on screen changes
        return when {
            // todo: if click is set on specific screens and we are no longer onPickup, send to picked-up handler
            screen == Screen.PICKUP_DETAILS_PICKED_UP -> AppState.DASH_ACTIVE_PICKED_UP
            // this isn't the only screen that can indicate the order was picked up.
            // need to dev the rest. (esp. shop orders, and multi-pickup orders)

            // TODO: next two... order cancelled?
//            screen == Screen.ON_DASH_ALONG_THE_WAY -> AppState.SESSION_ACTIVE_DASHING_ALONG_THE_WAY
//            screen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER -> AppState.SESSION_ACTIVE_WAITING_FOR_OFFER
            // TODO: do we need to transition on dash control anymore?
//            screen == Screen.DASH_CONTROL -> AppState.VIEWING_DASH_CONTROL
            // TODO: this should never happen.
//            screen == Screen.MAIN_MAP_IDLE -> AppState.DASHER_IDLE_OFFLINE
            // TODO: we don't want to transition out for navigation.
//            screen == Screen.NAVIGATION_VIEW -> AppState.VIEWING_NAVIGATION
//              screen == Screen.PICKUP_DETAILS_PRE_ARRIVAL -> AppState.VIEWING_PICKUP_DETAILS

            // new offer presented.
            screen == Screen.OFFER_POPUP -> AppState.DASH_ACTIVE_OFFER_PRESENTED

            // dasher viewing timeline and may switch tasks. need to transition.
            screen == Screen.TIMELINE_VIEW -> AppState.DASH_ACTIVE_ON_TIMELINE
            screen == Screen.DELIVERY_COMPLETED_DIALOG -> AppState.DASH_ACTIVE_DELIVERY_COMPLETED

            // main map idle?
            screen == Screen.MAIN_MAP_IDLE -> AppState.DASH_IDLE_OFFLINE


            // dasher went to a delivery screen.
            screen.isDelivery -> AppState.DASH_ACTIVE_ON_DELIVERY

            else -> {
                if (stateContext.currentDashState?.activeOrderId == null) {
                    Log.v(tag, "No active order. Attempting to match order...")
                    val activeOrder = OrderMatcher.matchOrder(stateContext)
                    if (activeOrder != null) {
                        currentRepo.updateActiveOrderFocus(activeOrder)
                        Log.d(tag, "Focused order: $activeOrder")
                    } else {
                        Log.d(tag, "No active order found.")
                        return currentState
                    }
                }
                updateFromContext(stateContext, currentState)
            }
        }
    }

    private suspend fun updateFromContext(
        stateContext: StateContext,
        currentState: AppState
    ): AppState {
        // In here, we just update the activeOrder that we focused in enterState.
        // (or, re-focused just before this)
        val activeOrderId =
            currentRepo.getCurrentDashState()?.activeOrderId ?: return currentState.also {
                Log.w(tag, "!!! No active order to update. !!!")
            }
        val screenInfo =
            stateContext.screenInfo as? ScreenInfo.OrderDetails ?: return currentState.also {
                Log.d(
                    tag,
                    "Screen info is not PickupDetails: ${stateContext.screenInfo}. Skipping update."
                )
            }
        val order = orderRepo.getOrderById(activeOrderId) ?: return currentState.also {
            Log.w(tag, "!!! Could not retrieve active order $activeOrderId from database. !!!")
        }

        // first, updates based on data we have from the PickupDetails.
        // if we have the data for the store, and the storeId for order is null, parse and set it.
        if (screenInfo.storeName != null &&
            screenInfo.storeAddress != null &&
            order.storeId == null
        ) {
            var storeId: Long? = null
            // check for existing stores at this address.
            val existingStoresAtAddress = storeRepo.getStoresByAddress(screenInfo.storeAddress)
            if (existingStoresAtAddress.isNotEmpty()) {
                // we found stores at this address. try to find a name match.
                for (existingStore in existingStoresAtAddress) {
                    if (UtilityFunctions.stringsMatch(
                            existingStore.storeName,
                            screenInfo.storeName
                        )
                    ) {
                        // found this store at this address, set the storeId.
                        storeId = existingStore.id
                        Log.i(
                            tag,
                            "Found existing store '${
                                existingStore.storeName
                            }' with same address. Using storeId: $storeId"
                        )
                        break
                    }

                }
            }
            // if no matching store found, this is a new store. upsert it.
            if (storeId == null) {
                Log.i(
                    tag,
                    "No existing store found at this address with a similar name. Creating a new store record."
                )
                val storeToUpsert = StoreEntity(
                    storeName = screenInfo.storeName,
                    address = screenInfo.storeAddress
                )
                storeId = storeRepo.upsertStore(storeToUpsert)
            }
            // now link the order to the store.
            orderRepo.linkOrderToStore(activeOrderId, storeId)
            Log.i(
                tag, "Updated Order ID $activeOrderId with Store ID $storeId: ${
                    screenInfo.storeName
                } at ${
                    screenInfo.storeAddress
                }."
            )
        }

        // if we have a customer name hash and the order doesn't have one, set it.
        if (screenInfo.customerNameHash != null && order.customerNameHash == null) {
            orderRepo.setCustomerNameHash(
                activeOrderId, screenInfo.customerNameHash
            )
            Log.i(
                tag,
                "Updated Order ID $activeOrderId with customer name hash: ${
                    screenInfo.customerNameHash
                }."
            )
        }

        // now, update the order status based on the screen we are on.
        when (screenInfo.screen) {

            Screen.NAVIGATION_VIEW_TO_PICK_UP,
            Screen.PICKUP_DETAILS_PRE_ARRIVAL,
            Screen.PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI -> {
                if (order.status != OrderStatus.PICKUP_NAVIGATING) {
                    orderRepo.updateOrderStatus(activeOrderId, OrderStatus.PICKUP_NAVIGATING)
                    Log.i(tag, "Updated Order ID $activeOrderId to status: PICKUP_NAVIGATING")
                }
            }

            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE,
            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_MULTI,
            Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP -> {
                if (order.status != OrderStatus.PICKUP_ARRIVED) {
                    orderRepo.updateOrderStatus(activeOrderId, OrderStatus.PICKUP_ARRIVED)
                    Log.i(tag, "Updated Order ID $activeOrderId to status: PICKUP_ARRIVED")
                }
            }

            else -> {
                Log.v(tag, "No specific update for screen ${screenInfo.screen}.")
            }
        }
        return currentState
    }

    override suspend fun exitState(
        stateContext: StateContext,
        currentState: AppState,
        nextState: AppState
    ) {
        Log.i(tag, "--- Exiting $tag ---")
    }
}