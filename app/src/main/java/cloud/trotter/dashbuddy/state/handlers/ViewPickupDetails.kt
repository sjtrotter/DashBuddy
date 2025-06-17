package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.store.StoreEntity
import cloud.trotter.dashbuddy.data.store.StoreParser
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen

// Placeholder/Example handlers for states not yet implemented by you
// You would replace these with your actual handler classes.
class ViewPickupDetails : StateHandler {

    private val currentRepo = DashBuddyApplication.currentRepo
    private val orderRepo = DashBuddyApplication.orderRepo
    private val storeRepo = DashBuddyApplication.storeRepo

    private val tag = this::class.simpleName ?: "ViewPickupDetails"

    override fun processEvent(
        context: StateContext,
        currentState: AppState
    ): AppState {


        // process the screen
        return when (context.dasherScreen) {
            Screen.DASH_CONTROL -> AppState.VIEWING_DASH_CONTROL
//            Screen.ON_DASH_ALONG_THE_WAY -> maybe?
//            Screen.TIMELINE_VIEW -> maybe add for timeline eventually?
            Screen.NAVIGATION_VIEW -> AppState.VIEWING_NAVIGATION
            Screen.OFFER_POPUP -> AppState.SESSION_ACTIVE_OFFER_PRESENTED
            Screen.DELIVERY_COMPLETED_DIALOG -> AppState.DELIVERY_COMPLETED
            else -> currentState
        }
    }

    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d(tag, "Entering state: DeliveryDetails")

        Manager.enqueueDbWork {
            try {
                // --- Initial Setup and Parsing ---
                val current = currentRepo.getCurrentDashState()
                val activeOrderQueue = current?.activeOrderQueue
                if (current == null || (activeOrderQueue.isNullOrEmpty() && current.activeOrderId == null)) {
                    Log.w(tag, "No active orders in queue. Cannot process.")
                    return@enqueueDbWork
                }

                val parsedStore = StoreParser.parseStoreDetails(context.rootNodeTexts)
                if (parsedStore == null) {
                    Log.w(tag, "Could not parse store details from the screen.")
                    return@enqueueDbWork
                }

                var storeId: Long? = null

                // --- NEW LOGIC: Search-First Strategy ---
                // 1. Search for existing stores at this exact address.
                val existingStoresAtAddress = storeRepo.getStoresByAddress(parsedStore.address)

                if (existingStoresAtAddress.isNotEmpty()) {
                    // 2. If we have stores at this address, try to find a name match.
                    for (existingStore in existingStoresAtAddress) {
                        if (namesMatch(existingStore.storeName, parsedStore.storeName)) {
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

                // --- Order Matching and Linking (Same as before) ---
                var matchedOrderId: Long? = null
                val allPossibleOrderIds =
                    (activeOrderQueue ?: emptyList()) + listOfNotNull(current.activeOrderId)

                if (allPossibleOrderIds.size == 1) {
                    matchedOrderId = allPossibleOrderIds.first()
                } else {
                    for (orderIdInQueue in allPossibleOrderIds.distinct()) {
                        val orderFromQueue = orderRepo.getOrderById(orderIdInQueue)
                        if (orderFromQueue != null &&
                            namesMatch(orderFromQueue.storeName, parsedStore.storeName)
                        ) {
                            matchedOrderId = orderFromQueue.id
                            break
                        }
                    }
                }

                if (matchedOrderId != null) {
                    orderRepo.linkOrderToStore(matchedOrderId, storeId)
                    Log.i(tag, "Updated Order ID $matchedOrderId with Store ID $storeId.")
                    DashBuddyApplication.sendBubbleMessage("Active Order: $matchedOrderId: $storeId: ${parsedStore.storeName}")
                    currentRepo.updateActiveOrderFocus(matchedOrderId)
                } else {
                    Log.w(tag, "Could not match the store on screen to any active order.")
                }

            } catch (e: Exception) {
                Log.e(tag, "!!! CRITICAL error in DeliveryDetails enterState !!!", e)
            }
        }
    }

    // Using a helper function to contain matching logic makes it cleaner and easier to improve later
    private fun namesMatch(nameFromOffer: String, nameFromPickupScreen: String): Boolean {
        val normalizedOfferName = normalize(nameFromOffer)
        val normalizedPickupName = normalize(nameFromPickupScreen)

        // Check for containment in both directions to handle cases where one name is a subset of the other
        return normalizedOfferName.contains(normalizedPickupName)
                || normalizedPickupName.contains(normalizedOfferName)
    }

    // Normalizes a string for better matching by lowercasing and removing non-alphanumeric chars
    private fun normalize(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9]"), "")
    }


    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.d(tag, "Exiting DeliveryDetails state")
    }
}
