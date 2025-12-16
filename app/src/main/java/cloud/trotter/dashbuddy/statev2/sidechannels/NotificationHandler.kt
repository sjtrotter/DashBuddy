package cloud.trotter.dashbuddy.statev2.sidechannels

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer
import cloud.trotter.dashbuddy.statev2.reducers.ReducerUtils

object NotificationHandler {

    fun handle(
        currentState: AppStateV2,
        context: StateContext
    ): Reducer.Transition? {
        val text = context.notificationText ?: return null

        val effects = mutableListOf<AppEffect>()

        // 1. Always Log the Raw Notification (for debugging/audit)
        val logEvent = ReducerUtils.createEvent(
            dashId = null, // Notifications are often async/outside a specific dash context
            type = AppEventType.NOTIFICATION_RECEIVED, // Ensure this exists in AppEventType!
            payload = text
        )
        effects.add(AppEffect.LogEvent(logEvent))

        // 2. Check for Specific "Tip Added" Logic
        if (text.contains("tip", ignoreCase = true) && text.contains("added", ignoreCase = true)) {
            // We found a tip! Delegate the parsing to an Effect.
            // We do NOT parse here because Reducers must be fast and synchronous.
            effects.add(AppEffect.ProcessTipNotification(text))
        }

        // Return the transition (State stays same, effects are queued)
        return Reducer.Transition(currentState, effects)
    }
}