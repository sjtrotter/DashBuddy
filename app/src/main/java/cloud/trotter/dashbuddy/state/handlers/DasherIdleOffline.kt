package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen

class DasherIdleOffline : StateHandler {

    private val nonZoneTexts = setOf(
        "Dash Now", "Dash Along the Way", "Open navigation drawer", "Navigate", "Promos",
        "Schedule", "Navigate up", "notification icon", "Notifications", "Safety",
        "Earn per Offer", "Got it", "© MAPBOX", "Help", "Close Webview",
        "Feeling unsafe? Get help here. Close Tooltip", "Getting Started",
        "Select end time", "Navigate up", "Schedule"
    )

    override fun processEvent(context: StateContext, currentState: AppState): AppState {
        Log.d("${this::class.simpleName} State", "Evaluating state...")
        // process event here

        // add more specific things up here if needed.

        if (context.dasherScreen == Screen.MAIN_MENU_VIEW) return AppState.VIEWING_MAIN_MENU
        if (context.dasherScreen == Screen.NOTIFICATIONS_VIEW) return AppState.VIEWING_NOTIFICATIONS
        if (context.dasherScreen == Screen.SAFETY_VIEW) return AppState.VIEWING_SAFETY
        if (context.dasherScreen == Screen.PROMOS_VIEW) return AppState.VIEWING_PROMOS
        if (context.dasherScreen == Screen.HELP_VIEW) return AppState.VIEWING_HELP
        if (context.dasherScreen == Screen.CHAT_VIEW) return AppState.VIEWING_CHATS
        if (context.dasherScreen == Screen.SET_DASH_END_TIME) return AppState.DASHER_SETTING_END_TIME

        // Seems that the on dash map waiting for offer might recognize the main map idle screen
        // when backing out to dash control. putting this in to catch and re-orient.
        if (context.dasherScreen == Screen.DASH_CONTROL) return AppState.VIEWING_DASH_CONTROL

        // If the dasher is not Platinum and not scheduled to dash,
        // then Dash Now button is replaced with Schedule button.
        if (context.dasherScreen == Screen.SCHEDULE_VIEW) return AppState.VIEWING_SCHEDULE

        // If from here we see the on map screen waiting for offer, then we either
        // just started a dash or we're resuming a dash.
        // TODO: add check to Current table to see if a dash is started.


        // if a dash is not started:
        if (context.dasherScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER ||
            context.dasherScreen == Screen.ON_DASH_ALONG_THE_WAY
        )
            return AppState.DASHER_INITIATING_DASH_SESSION

        if (context.sourceNodeTexts.isNotEmpty()) {
            if (!nonZoneTexts.contains(context.sourceNodeTexts[0]) &&
                !context.sourceNodeTexts[0].contains("Earn by Time") &&
                !context.sourceNodeTexts[0].matches(Regex("^\\d{1,2}:\\d{2}$"))
            ) {
                Log.i("${this::class.simpleName} State", "Zone? - ${context.sourceNodeTexts[0]}")
                Manager.setPreDashZone(context.sourceNodeTexts[0])
            }
        }


        return currentState
    }

    override fun enterState(
        context: StateContext,
        currentState: AppState,
        previousState: AppState?
    ) {
        Log.d("${this::class.simpleName} State", "Entering state...")
        // initialize components here

//        val message = SpannableStringBuilder()
//            .apply {
//                val boldStart = length
//                append("State:\t\t")
//                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), boldStart, length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
//            }
//            .append("${currentState.displayName}\n")
//            .apply {
//                val boldStart = length
//                append("Screen:\t")
//                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), boldStart, length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
//            }
//            .append(context.dasherScreen?.screenName)

        DashBuddyApplication.sendBubbleMessage("${currentState.displayName} State\n${context.dasherScreen?.screenName} Screen")
//        DashBuddyApplication.sendBubbleMessage(message)
    }

    override fun exitState(context: StateContext, currentState: AppState, nextState: AppState) {
        Log.d("${this::class.simpleName} State", "Exiting state...")
    }
}