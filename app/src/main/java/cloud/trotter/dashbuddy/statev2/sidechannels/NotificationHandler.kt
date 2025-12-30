package cloud.trotter.dashbuddy.statev2.sidechannels

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer
import cloud.trotter.dashbuddy.statev2.reducers.ReducerUtils

object NotificationHandler {

    // Keywords to filter noise
    private val KEYWORDS = listOf("New Order", "Tip", "Review", "Dasher", "Complete", "Paid")

    fun handle(
        currentState: AppStateV2,
        context: StateContext
    ): Reducer.Transition? {
        val notif = context.notification ?: return null
        val fullText = notif.toFullString()

        val effects = mutableListOf<AppEffect>()

        // 1. Log Event (Filtered)
        if (KEYWORDS.any { fullText.contains(it, ignoreCase = true) }) {
            val logEvent = ReducerUtils.createEvent(
                dashId = currentState.dashId,
                type = AppEventType.NOTIFICATION_RECEIVED,
                payload = fullText
            )
            effects.add(AppEffect.LogEvent(logEvent))
        }

        // 2. Logic (Tip Detection)
        if (fullText.contains("tip", true) && fullText.contains("added", true)) {
            effects.add(AppEffect.ProcessTipNotification(fullText))
        }

        // Return null if we ignored it (allows Reducer to proceed to Screen logic if needed,
        // though usually notification events are standalone)
        if (effects.isEmpty()) return null

        return Reducer.Transition(currentState, effects)
    }
}