package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.factories.AwaitingStateFactory
import cloud.trotter.dashbuddy.state.factories.DeliveryStateFactory
import cloud.trotter.dashbuddy.state.factories.OfferStateFactory
import cloud.trotter.dashbuddy.state.factories.PickupStateFactory
import cloud.trotter.dashbuddy.state.model.TimeoutType
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PostDeliveryReducer @Inject constructor(
    private val awaitingStateFactory: AwaitingStateFactory,
    private val deliveryStateFactory: DeliveryStateFactory,
    private val pickupStateFactory: PickupStateFactory,
    private val offerStateFactory: OfferStateFactory,
) {

    companion object {
        private const val MAX_CLICK_ATTEMPTS = 3
        private const val GLITCH_RETRY_DELAY = 1500L
    }

    fun reduce(state: AppStateV2.PostDelivery, input: ScreenInfo): Transition? {

        // 1. EXIT LOGIC: User has left the screen.
        val exitTransition = checkForExit(state, input)
        if (exitTransition != null) {
            return attachFinalRecording(state, exitTransition)
        }

        // 2. CLOSED LOOP LOGIC
        return when (input) {
            is ScreenInfo.DeliverySummary -> handleDeliverySummary(state, input)
            else -> null
        }
    }

    private fun handleDeliverySummary(
        state: AppStateV2.PostDelivery,
        input: ScreenInfo.DeliverySummary
    ): Transition? {
        var newState = state

        // --- PHASE 1: Data Accumulation ---
        // If we found a full breakdown, capture it immediately.
        if (input.parsedPay != null && input.parsedPay != state.parsedPay) {
            newState = newState.copy(
                parsedPay = input.parsedPay,
                totalPay = input.parsedPay.total,
                summaryText = "Saved: ${UtilityFunctions.formatCurrency(input.parsedPay.total)}"
            )
            // Optional: Show bubble immediately
            return Transition(
                newState,
                listOf(
                    AppEffect.UpdateBubble(
                        generateReceiptText(
                            input.parsedPay,
                            input.parsedPay.total
                        ), ChatPersona.Earnings
                    )
                )
            )
        }

        // If we haven't found a breakdown, at least grab the header total.
        if (state.parsedPay == null && input.totalPay > 0 && state.totalPay == 0.0) {
            newState = newState.copy(
                totalPay = input.totalPay,
                summaryText = "Saved: ${UtilityFunctions.formatCurrency(input.totalPay)}"
            )
        }

        // --- PHASE 2: Automation (Closed Loop) ---
        // Goal: Get the breakdown (parsedPay).
        if (newState.parsedPay == null) {

            // We see a button...
            if (input.expandButton != null) {
                val canRetry = state.clickAttempts < MAX_CLICK_ATTEMPTS

                // Logic: If we haven't clicked recently (clickSent == false), AND we have retries left.
                if (!state.clickSent && canRetry) {
                    val nextAttempt = state.clickAttempts + 1
                    Timber.d("Clicking Expand. Attempt $nextAttempt / $MAX_CLICK_ATTEMPTS")

                    return Transition(
                        newState.copy(
                            clickSent = true,
                            clickAttempts = nextAttempt
                        ),
                        listOf(
                            // Action
                            AppEffect.ClickNode(input.expandButton, "Expand Details"),
                            // Observation: Check back in 1.5s
                            AppEffect.ScheduleTimeout(GLITCH_RETRY_DELAY, TimeoutType.VERIFY_PAY)
                        )
                    )
                }
            }
        }

        return if (newState != state) Transition(newState) else null
    }

    fun onTimeout(state: AppStateV2.PostDelivery, event: TimeoutEvent): Transition {
        if (event.type == TimeoutType.VERIFY_PAY) {
            // The timer expired. This means 1.5s passed since we clicked.

            // If we have data now, great! Do nothing.
            if (state.parsedPay != null) {
                return Transition(state)
            }

            // If we are still here and data is missing, the click failed (glitch).
            // Reset 'clickSent' to false. This allows the reducer (in the next frame)
            // to enter the "canRetry" block again.
            Timber.w("Expansion verification failed. Resetting click flag to retry.")
            return Transition(
                state.copy(clickSent = false)
            )
        }
        return Transition(state)
    }

    // --- Helpers ---

    private fun checkForExit(state: AppStateV2.PostDelivery, input: ScreenInfo): Transition? {
        return when (input) {
            is ScreenInfo.WaitingForOffer -> awaitingStateFactory.createEntry(state, input, false)
            is ScreenInfo.DropoffDetails -> deliveryStateFactory.createEntry(state, input, false)
            is ScreenInfo.PickupDetails -> pickupStateFactory.createEntry(state, input, false)
            is ScreenInfo.Offer -> offerStateFactory.createEntry(state, input, false)
            else -> null
        }
    }

    private fun attachFinalRecording(
        state: AppStateV2.PostDelivery,
        exitTransition: Transition
    ): Transition {
        val payload: Any = state.parsedPay
            ?: mapOf(
                "total" to state.totalPay,
                "warning" to "Collapsed Data Only - Breakdown Missing"
            )

        val logEffect = AppEffect.LogEvent(
            ReducerUtils.createEvent(
                dashId = state.dashId,
                type = AppEventType.DELIVERY_COMPLETED,
                payload = payload
            )
        )

        return exitTransition.copy(
            effects = listOf(logEffect) + exitTransition.effects
        )
    }

    private fun generateReceiptText(data: ParsedPay, total: Double): String {
        val sb = StringBuilder()
        sb.append("Saved: ${UtilityFunctions.formatCurrency(total)}")
        data.customerTips.forEach { item ->
            sb.append("\nTip: ${item.type} • ${UtilityFunctions.formatCurrency(item.amount)}")
        }
        return sb.toString()
    }
}