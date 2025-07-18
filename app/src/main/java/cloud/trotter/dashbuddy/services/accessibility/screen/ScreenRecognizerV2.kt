package cloud.trotter.dashbuddy.services.accessibility.screen

import cloud.trotter.dashbuddy.data.offer.OfferParser
import cloud.trotter.dashbuddy.data.pay.PayParser
import cloud.trotter.dashbuddy.services.accessibility.screen.parsers.DeliveryScreen
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.services.accessibility.screen.parsers.IdleMapParser
import cloud.trotter.dashbuddy.services.accessibility.screen.parsers.PickupScreen

object ScreenRecognizerV2 {
    private const val TAG = "ScreenRecognizerV2"

    private val screenCheckOrder: List<Screen> = listOf(
        // High-priority, often modal or overlay screens
        Screen.OFFER_POPUP_CONFIRM_DECLINE,
        Screen.OFFER_POPUP,
        Screen.DELIVERY_COMPLETED_DIALOG,

        // Pickup Flow
        Screen.PICKUP_DETAILS_PRE_ARRIVAL,
        Screen.PICKUP_DETAILS_PRE_ARRIVAL_PICKUP_MULTI,
        Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP,
        Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_SINGLE,
        Screen.PICKUP_DETAILS_POST_ARRIVAL_PICKUP_MULTI,
        Screen.PICKUP_DETAILS_VERIFY_PICKUP,
        Screen.PICKUP_DETAILS_PICKED_UP,

        // Dropoff Flow
        Screen.DROPOFF_DETAILS_PRE_ARRIVAL,
//        Screen.DROPOFF_DETAILS_POST_ARRIVAL,

        // Broader active states
        Screen.NAVIGATION_VIEW_TO_PICK_UP,
        Screen.NAVIGATION_VIEW_TO_DROP_OFF,
        Screen.ON_DASH_MAP_WAITING_FOR_OFFER,
        Screen.DASH_CONTROL,
        Screen.ON_DASH_ALONG_THE_WAY,
        Screen.TIMELINE_VIEW,
        Screen.NAVIGATION_VIEW,
//        Screen.DASH_PAUSED_SCREEN,

        // Post-dash or pre-dash setup
        Screen.DASH_SUMMARY_SCREEN,
        Screen.SET_DASH_END_TIME,

        // Main app sections when idle/offline
        Screen.MAIN_MAP_IDLE,
        Screen.MAIN_MENU_VIEW,
        Screen.EARNINGS_VIEW,
        Screen.SCHEDULE_VIEW,
        Screen.RATINGS_VIEW,
        Screen.CHAT_VIEW,
        Screen.HELP_VIEW,
        Screen.SAFETY_VIEW,
        Screen.NOTIFICATIONS_VIEW,
        Screen.PROMOS_VIEW,

        // Initial app states
        Screen.LOGIN_SCREEN,
        Screen.APP_STARTING_OR_LOADING

        // UNKNOWN is the implicit fallback if none of these match in the loop.
    )

    fun identify(stateContext: StateContext): ScreenInfo {
        for (screenCandidate in screenCheckOrder) {
            if (screenCandidate.matches(stateContext)) {
                Log.i(TAG, "V2 Matched Screen: $screenCandidate")

                // This is where we delegate to the correct parser
                return when {
                    screenCandidate == Screen.OFFER_POPUP -> {
                        val parsedOffer = OfferParser.parseOffer(stateContext.rootNodeTexts)
                        parsedOffer?.let { ScreenInfo.Offer(screenCandidate, it) }
                            ?: ScreenInfo.Simple(screenCandidate) // Fallback
                    }

                    screenCandidate.isPickup -> {
                        // val parsedStore = StoreParser.parseStoreDetails(stateContext.rootNodeTexts)
                        // parsedStore?.let { ScreenInfo.PickupDetails(screenCandidate, it) }
                        //     ?: ScreenInfo.Simple(screenCandidate) // Fallback
                        return PickupScreen.parse(stateContext.rootNodeTexts, screenCandidate)

                    }

                    screenCandidate.isDelivery -> {
                        return DeliveryScreen.parse(stateContext.rootNodeTexts, screenCandidate)
                    }

                    screenCandidate == Screen.DELIVERY_COMPLETED_DIALOG -> {
                        val parsedPay = PayParser.parsePay(stateContext.rootNodeTexts)
                        ScreenInfo.DeliveryCompleted(screenCandidate, parsedPay)
                    }

                    screenCandidate == Screen.MAIN_MAP_IDLE -> {
                        val (zone, type) = IdleMapParser.parse(stateContext.rootNodeTexts)
                        ScreenInfo.IdleMap(screenCandidate, zone, type)
                    }

                    else -> {
                        // For any other screen, just return the simple type
                        ScreenInfo.Simple(screenCandidate)
                    }
                }
            }
        }
        return ScreenInfo.Simple(Screen.UNKNOWN)
    }
}