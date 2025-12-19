package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer

object PostDeliveryReducer {

    // --- FACTORY ---
    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.DeliveryCompleted,
        isRecovery: Boolean
    ): Reducer.Transition {
        val total = input.parsedPay.total

        // Deduplication: If we are simply refreshing the same payout screen, don't re-log.
        if (oldState is AppStateV2.PostDelivery && oldState.totalPay == total) {
            return Reducer.Transition(oldState)
        }

        val newState = AppStateV2.PostDelivery(
            dashId = oldState.dashId,
            totalPay = total,
            summaryText = "Paid: $$total"
        )

        val effects = mutableListOf<AppEffect>()

        // LOGIC: We always log this event because it represents a distinct financial transaction.
        // Even in recovery, if we see a payout screen we haven't recorded, we should probably log it.
        // (For now, we treat recovery same as normal flow to ensure data capture).

        effects.add(
            AppEffect.LogEvent(
                ReducerUtils.createEvent(
                    dashId = oldState.dashId,
                    type = AppEventType.DELIVERY_COMPLETED,
                    payload = input.parsedPay // Serializes the full pay breakdown
                )
            )
        )

        // UI & Screenshot
        val filename = "payout_${total}_${System.currentTimeMillis()}"
        effects.add(AppEffect.CaptureScreenshot(filename))
        effects.add(AppEffect.UpdateBubble("Saved! $$total"))

        return Reducer.Transition(newState, effects)
    }

    // --- REDUCER ---
    fun reduce(
        state: AppStateV2.PostDelivery,
        input: ScreenInfo
    ): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.DeliveryCompleted -> {
                // User is staring at the payout screen. No change.
                Reducer.Transition(state)
            }

            is ScreenInfo.WaitingForOffer -> {
                // User clicked "Done" or "Resume Dash" -> Back to searching
                AwaitingReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.IdleMap -> {
                // User ended dash immediately after delivery
                IdleReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.DashSummary -> {
                // User ended dash and is viewing summary
                SummaryReducer.transitionTo(state, input, isRecovery = false)
            }

            is ScreenInfo.DashPaused -> {
                // User set dash to pause after delivery
                DashPausedReducer.transitionTo(state, input, isRecovery = false)
            }

            else -> null
        }
    }
}