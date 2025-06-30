package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.order.OrderStatus
import cloud.trotter.dashbuddy.data.store.ParsedStore
import cloud.trotter.dashbuddy.data.store.StoreEntity
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.AppState
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.StateContext as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
//import cloud.trotter.dashbuddy.state.processing.CustomerProcessor
//import cloud.trotter.dashbuddy.state.processing.StoreProcessor
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

    override suspend fun enterState(
        stateContext: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
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
        // Determine next state based on screen changes
        return when {
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
        val activeOrderId =
            currentRepo.getCurrentDashState()?.activeOrderId ?: return currentState.also {
                Log.d(tag, "No active order to update.")
            }
        val screenInfo =
            stateContext.screenInfo as? ScreenInfo.OrderDetails ?: return currentState.also {
                Log.d(tag, "Screen info is not PickupDetails. Skipping update.")
            }
        val order = orderRepo.getOrderById(activeOrderId) ?: return currentState.also {
            Log.w(tag, "!!! Could not retrieve active order $activeOrderId from database. !!!")
        }

        when (screenInfo.screen) {

            Screen.NAVIGATION_VIEW_TO_PICK_UP,
            Screen.PICKUP_DETAILS_PRE_ARRIVAL -> {
                // ScreenInfo.PickupDetails(screen, storeName, storeAddress, null)
                if (order.storeId == null) {
                    val parsedStore = ParsedStore(
                        screenInfo.storeName!!,
                        screenInfo.storeAddress!!,
                    )
                    var storeId: Long? = null

                    // 1. Search for existing stores at this exact address.
                    val existingStoresAtAddress = storeRepo.getStoresByAddress(parsedStore.address)

                    if (existingStoresAtAddress.isNotEmpty()) {
                        // 2. If we have stores at this address, try to find a name match.
                        for (existingStore in existingStoresAtAddress) {
                            if (UtilityFunctions.stringsMatch(
                                    existingStore.storeName,
                                    parsedStore.storeName
                                )
                            ) {
                                storeId = existingStore.id
                                Log.i(
                                    tag,
                                    "Found existing store '${existingStore.storeName}' with same address. Using storeId: $storeId"
                                )
                                break
                            }
                        }
                    }

                    // 3. If no matching store was found, this is a new store. Upsert it.
                    if (storeId == null) {
                        Log.i(
                            tag,
                            "No existing store found at this address with a similar name. Creating a new store record."
                        )
                        val storeToUpsert = StoreEntity(
                            storeName = parsedStore.storeName,
                            address = parsedStore.address
                        )
                        storeId = storeRepo.upsertStore(storeToUpsert)
                    }
                    orderRepo.linkOrderToStore(activeOrderId, storeId)
                    Log.i(tag, "Updated Order ID $activeOrderId with Store ID $storeId.")
                }
                if (order.status != OrderStatus.PICKUP_NAVIGATING) {
                    orderRepo.updateOrderStatus(activeOrderId, OrderStatus.PICKUP_NAVIGATING)
                    Log.i(tag, "Updated Order ID $activeOrderId to status: PICKUP_NAVIGATING")
                }
            }

            Screen.PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI,
            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_MULTI -> {
                // ScreenInfo.PickupDetails(screen, storeName, null, null)
                // ...what exactly am i doing here?
            }

            Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE -> {
                // ScreenInfo.PickupDetails(screen, storeName, null, customerHash)
                if (order.customerNameHash == null && screenInfo.customerNameHash != null) {
                    orderRepo.setCustomerNameHash(activeOrderId, screenInfo.customerNameHash)
                    Log.i(
                        tag,
                        "Updated Order ID $activeOrderId with Customer Name Hash: ${screenInfo.customerNameHash}"
                    )
                }
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