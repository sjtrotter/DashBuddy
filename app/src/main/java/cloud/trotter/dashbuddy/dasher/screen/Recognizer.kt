package cloud.trotter.dashbuddy.dasher.screen

import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.log.Logger as Log

object Recognizer {

    private const val TAG = "ScreenRecognizer"

    // Define the order in which to check screens.
    // More specific screens (e.g., popups, dialogs that can overlay others)
    // should generally come before more general, full-screen states.
    val screenCheckOrder: List<Screen> = listOf(
        // High-priority, often modal or overlay screens
        Screen.OFFER_POPUP,
        Screen.DELIVERY_COMPLETED_DIALOG,
        Screen.PICKUP_DETAILS_PRE_ARRIVAL,
//        Screen.DECLINE_OFFER_CONFIRM_MODAL,
//        Screen.DECLINE_OFFER_REASON_MODAL, // Added from your logs
//        Screen.END_DASH_CONFIRMATION_DIALOG,
//        Screen.DELIVERY_CONFIRM_PICKUP_DIALOG,
//        Screen.DELIVERY_WAITING_AT_STORE_DIALOG,
//        Screen.DELIVERY_CONFIRM_DIALOG,

        // Specific task screens
//        Screen.DELIVERY_TAKE_PHOTO_UI,
//        Screen.DELIVERY_REVIEW_PHOTO_UI,
//        Screen.DELIVERY_TAKE_RECEIPT_PHOTO,
//        Screen.DELIVERY_REVIEW_RECEIPT_PHOTO,
//        Screen.DELIVERY_SHOP_AND_DELIVER_SCANNING,
//        Screen.DELIVERY_SHOP_AND_DELIVER_ITEM_UNAVAILABLE,
//        Screen.DELIVERY_SHOP_AND_DELIVER_CHECKOUT,
//        Screen.DELIVERY_COMPLETE_STEPS_LIST,
//        Screen.DELIVERY_SHOP_AND_DELIVER_LIST,
//        Screen.DELIVERY_PROBLEM_REPORTING,

        // Broader active states
//        Screen.DELIVERY_ARRIVED_AT_CUSTOMER,
//        Screen.DELIVERY_NAVIGATION_TO_CUSTOMER,
//        Screen.DELIVERY_ARRIVED_AT_STORE,
//        Screen.DELIVERY_NAVIGATION_TO_STORE,
        Screen.NAVIGATION_VIEW_TO_PICK_UP,
        Screen.NAVIGATION_VIEW_TO_DROP_OFF,
        Screen.ON_DASH_MAP_WAITING_FOR_OFFER,
        Screen.DASH_CONTROL,
        Screen.ON_DASH_ALONG_THE_WAY,
        Screen.TIMELINE_VIEW,
        Screen.NAVIGATION_VIEW,
//        Screen.DASH_PAUSED_SCREEN,

        // Post-dash or pre-dash setup
//        Screen.DASH_SUMMARY_SCREEN,
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
//        Screen.PROMOS_VIEW,
//        Screen.ACCOUNT_DETAILS_VIEW,
//        Screen.SETTINGS_MENU_VIEW,
//        Screen.DASHER_HELP_VIEW,
//        Screen.CHAT_VIEW,
//        Screen.SAFETY_TOOLKIT_VIEW,

        // Initial app states
        Screen.LOGIN_SCREEN,
        Screen.APP_STARTING_OR_LOADING

        // UNKNOWN is the implicit fallback if none of these match in the loop.
    )

    fun identify(stateContext: StateContext, previousScreen: Screen? = null): Screen {
        // 1. Log screen texts if they exist, with newlines handled.
        if (stateContext.rootNodeTexts.isNotEmpty()) {
            Log.v(TAG, "Screen Texts: [${stateContext.rootNodeTexts}]")
        } else {
            Log.v(
                TAG,
                "No screen texts. Event: ${stateContext.eventTypeString}, SourceClass: ${stateContext.sourceClassName}"
            )
        }

        // 2. Log source texts if they exist, with newlines handled.
        if (stateContext.sourceNodeTexts.isNotEmpty()) {
            Log.v(TAG, "Source Texts: [${stateContext.sourceNodeTexts}]")
        } else {
            Log.v(
                TAG,
                "No source texts. Event: ${stateContext.eventTypeString}, SourceClass: ${stateContext.sourceClassName}"
            )
        }

        for (screenCandidate in screenCheckOrder) {
            if (screenCandidate.matches(stateContext)) {
                Log.i(TAG, "Screen Identified As: $screenCandidate")
                return screenCandidate
            }
        }

        if (previousScreen != null) {
            return previousScreen
        } else {
            Log.w(
                TAG,
                "Screen UNKNOWN. No signature matched. Texts (first 10): ${
                    stateContext.rootNodeTexts.take(10).joinToString(" | ")
                }, SourceClass: ${stateContext.sourceClassName}"
            )
            return Screen.UNKNOWN
        }
    }
}