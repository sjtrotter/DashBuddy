package cloud.trotter.dashbuddy.state.screens

import cloud.trotter.dashbuddy.state.Context
import cloud.trotter.dashbuddy.log.Logger as Log

object Recognizer {

    private const val TAG = "ScreenRecognizer"

    // Define the order in which to check screens.
    // More specific screens (e.g., popups, dialogs that can overlay others)
    // should generally come before more general, full-screen states.
    private val screenCheckOrder: List<Screen> = listOf(
        // High-priority, often modal or overlay screens
        Screen.OFFER_POPUP,
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

    fun identify(context: Context, previousScreen: Screen? = null): Screen {
        // Log texts for debugging the recognizer itself
        if (context.screenTexts.isNotEmpty()) {
            // Limit logged texts to avoid overly verbose logs if screenTexts is huge
            val textsToLog = if (context.screenTexts.size > 20) {
                context.screenTexts.take(20).joinToString(" | ") + " ... (and more)"
            } else {
                context.screenTexts.joinToString(" | ")
            }
            Log.v(TAG, "Attempting to identify screen from texts: [$textsToLog]")
        } else if (context.eventTypeString != "INITIALIZATION") { // Don't log for the very first init context
            Log.v(
                TAG,
                "Attempting to identify screen: No screen texts. Event: ${context.eventTypeString}, SourceClass: ${context.sourceClassName}"
            )
        }


        for (screenCandidate in screenCheckOrder) {
            if (screenCandidate.matches(context)) {
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
                    context.screenTexts.take(10).joinToString(" | ")
                }, SourceClass: ${context.sourceClassName}"
            )
            return Screen.UNKNOWN
        }
    }
}