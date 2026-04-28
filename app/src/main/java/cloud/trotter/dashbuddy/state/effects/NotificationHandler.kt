package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.state.NotificationEvent
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationHandler @Inject constructor() {

    fun handle(
        currentState: AppStateV2,
        event: NotificationEvent,
    ): Transition {
        val effects = mutableListOf<AppEffect>()

        when (val info = event.info) {
            is NotificationInfo.AdditionalTip -> {
                effects.add(
                    AppEffect.ProcessTipNotification(
                        amount = info.amount,
                        storeName = info.storeName,
                        deliveredAt = info.deliveredAt,
                    )
                )
                val logEvent = ReducerUtils.createEvent(
                    dashId = currentState.dashId,
                    type = AppEventType.NOTIFICATION_RECEIVED,
                    payload = "TIP_ADDED: \$${info.amount} from ${info.storeName}"
                )
                effects.add(AppEffect.LogEvent(logEvent))
            }

            is NotificationInfo.NewOrder -> {
                // Screen pipeline drives offer state; log the signal but take no action.
                val logEvent = ReducerUtils.createEvent(
                    dashId = currentState.dashId,
                    type = AppEventType.NOTIFICATION_RECEIVED,
                    payload = "NEW_ORDER"
                )
                effects.add(AppEffect.LogEvent(logEvent))
            }

            is NotificationInfo.ScheduledDashExpired -> {
                val logEvent = ReducerUtils.createEvent(
                    dashId = currentState.dashId,
                    type = AppEventType.NOTIFICATION_RECEIVED,
                    payload = "SCHEDULED_DASH_EXPIRED"
                )
                effects.add(AppEffect.LogEvent(logEvent))
            }

            is NotificationInfo.Unknown -> {
                // Log raw text so we can identify new notification types in the field.
                Timber.d("NotificationHandler: UNKNOWN notification — ${info.rawText}")
                val logEvent = ReducerUtils.createEvent(
                    dashId = currentState.dashId,
                    type = AppEventType.NOTIFICATION_RECEIVED,
                    payload = "UNKNOWN: ${info.rawText}"
                )
                effects.add(AppEffect.LogEvent(logEvent))
            }
        }

        return Transition(currentState, effects)
    }
}
