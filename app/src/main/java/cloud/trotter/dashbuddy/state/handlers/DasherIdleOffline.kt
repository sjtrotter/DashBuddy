package cloud.trotter.dashbuddy.state.handlers

import cloud.trotter.dashbuddy.DashBuddyApplication
import cloud.trotter.dashbuddy.data.current.CurrentEntity
import cloud.trotter.dashbuddy.state.Manager
import cloud.trotter.dashbuddy.state.ScreenInfo
import cloud.trotter.dashbuddy.log.Logger as Log
import cloud.trotter.dashbuddy.state.App as AppState
import cloud.trotter.dashbuddy.state.Context as StateContext
import cloud.trotter.dashbuddy.state.StateHandler
import cloud.trotter.dashbuddy.state.screens.Screen

class DasherIdleOffline : StateHandler {

    private val currentRepo = DashBuddyApplication.currentRepo
    private val zoneRepo = DashBuddyApplication.zoneRepo

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

        // if a dash is not started:
        if (context.dasherScreen == Screen.ON_DASH_MAP_WAITING_FOR_OFFER ||
            context.dasherScreen == Screen.ON_DASH_ALONG_THE_WAY
        )
            return AppState.DASHER_INITIATING_DASH_SESSION

        if (context.screenInfo is ScreenInfo.IdleMap) {
            Manager.enqueueDbWork {
                try {
                    // Get the current state from the DB, or create a default empty one
                    val currentData = currentRepo.getCurrentDashState() ?: CurrentEntity()

                    // Get the new data from the screen parser
                    val newZoneId =
                        context.screenInfo.zoneName?.let { zoneRepo.getOrInsertZone(it) }

                    val newDashType = context.screenInfo.dashType

                    // Check if an update is actually needed
                    val needsUpdate =
                        (currentData.zoneId != newZoneId) || (currentData.dashType != newDashType)

                    if (needsUpdate) {
                        // If and only if something changed, perform a SINGLE update
                        // with both new values.
                        currentRepo.updatePreDashInfo(newZoneId, newDashType)
                        Log.i("DasherIdleOffline", "Pre-dash info changed, updating Current table.")
                    }

                } catch (e: Exception) {
                    Log.e("DasherIdleOffline", "!!! Error updating pre-dash zone/type data. !!!", e)
                }
            }
        } else {
            Log.d("DasherIdleOffline", "ScreenInfo is not IdleMap: ${context.screenInfo}")
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