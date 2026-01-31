package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.Reducer
import cloud.trotter.dashbuddy.state.reducers.offer.OfferReducer
import cloud.trotter.dashbuddy.state.reducers.postdelivery.PostDeliveryReducer
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class AwaitingReducer @Inject constructor(
    private val idleReducerProvider: Provider<IdleReducer>,
    private val bubbleManager: BubbleManager,
    private val deliveryReducerProvider: Provider<DeliveryReducer>,
    private val pickupReducerProvider: Provider<PickupReducer>,
    private val summaryReducer: SummaryReducer,
    private val dashPausedReducerProvider: Provider<DashPausedReducer>,
    private val postDeliveryReducerProvider: Provider<PostDeliveryReducer>,
    private val offerReducerProvider: Provider<OfferReducer>,
) {

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.WaitingForOffer,
        isRecovery: Boolean
    ): Reducer.Transition {
        // Reuse ID if valid, else generate new
        val dashId = oldState.dashId ?: UUID.randomUUID().toString()

        val newState = AppStateV2.AwaitingOffer(
            dashId = dashId,
            currentSessionPay = input.currentDashPay,
            waitTimeEstimate = input.waitTimeEstimate,
            isHeadingBackToZone = input.isHeadingBackToZone
        )

        val effects = mutableListOf<AppEffect>()

        // Log DASH_START only if we are coming from offline or initializing
        if (oldState is AppStateV2.IdleOffline || oldState is AppStateV2.Initializing || (isRecovery && oldState.dashId == null)) {
            val payload = mapOf(
                "source" to if (isRecovery) "recovery" else "interaction",
                "start_screen" to "WaitingForOffer"
            )

            bubbleManager.startDash(dashId)
            val event = ReducerUtils.createEvent(dashId, AppEventType.DASH_START, payload)
            effects.add(AppEffect.LogEvent(event))
            effects.add(AppEffect.StartOdometer)
            effects.add(AppEffect.UpdateBubble("Dash Started!", ChatPersona.Dispatcher))
        }

        return Reducer.Transition(newState, effects)
    }

    fun reduce(
        state: AppStateV2.AwaitingOffer,
        input: ScreenInfo
    ): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.WaitingForOffer -> {
                if (state.currentSessionPay != input.currentDashPay || state.waitTimeEstimate != input.waitTimeEstimate) {
                    Reducer.Transition(
                        state.copy(
                            currentSessionPay = input.currentDashPay,
                            waitTimeEstimate = input.waitTimeEstimate
                        )
                    )
                } else Reducer.Transition(state)
            }

            is ScreenInfo.Offer -> offerReducerProvider.get()
                .transitionTo(state, input, isRecovery = false)

            is ScreenInfo.IdleMap -> idleReducerProvider.get()
                .transitionTo(state, input, isRecovery = false)

            is ScreenInfo.DashPaused -> dashPausedReducerProvider.get().transitionTo(
                state,
                input,
                isRecovery = false
            )

            is ScreenInfo.DeliverySummaryCollapsed -> postDeliveryReducerProvider.get()
                .transitionTo(
                    state,
                    input,
                    isRecovery = false
                )

            is ScreenInfo.DeliveryCompleted -> postDeliveryReducerProvider.get().transitionTo(
                state,
                input,
                isRecovery = false
            )

            is ScreenInfo.PickupDetails -> pickupReducerProvider.get().transitionTo(
                state,
                input,
                isRecovery = false
            )

            is ScreenInfo.DropoffDetails -> deliveryReducerProvider.get().transitionTo(
                state,
                input,
                isRecovery = false
            )

            is ScreenInfo.DashSummary -> summaryReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            else -> null
        }
    }
}