package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils

object NotificationHandler {

    // Keywords to filter noise
    private val KEYWORDS = listOf("New Order", "Tip", "Review", "Dasher", "Complete", "Paid")

    fun handle(
        currentState: AppStateV2,
        stateEvent: NotificationEvent,
    ): Reducer.Transition {
        val notif = stateEvent.notification
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

        return Reducer.Transition(currentState, effects)
    }
}