package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.NotificationEvent
import cloud.trotter.dashbuddy.state.model.Transition // <--- New Import
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHandler @Inject constructor() {

    // Keywords to filter noise
    private val keywords = listOf("New Order", "Tip", "Review", "Dasher", "Complete", "Paid")

    fun handle(
        currentState: AppStateV2,
        stateEvent: NotificationEvent,
    ): Transition { // <--- Return type updated
        val notif = stateEvent.notification
        val fullText = notif.toFullString()

        val effects = mutableListOf<AppEffect>()

        // 1. Log Event (Filtered)
        if (keywords.any { fullText.contains(it, ignoreCase = true) }) {
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

        // Return new Transition object directly
        return Transition(currentState, effects)
    }
}