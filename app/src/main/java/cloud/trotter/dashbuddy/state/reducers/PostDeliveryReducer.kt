package cloud.trotter.dashbuddy.state.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.event.ScreenUpdateEvent
import cloud.trotter.dashbuddy.state.event.StateEvent
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
        private const val UI_SETTLE_DELAY = 500L // New: Let the slide-in finish
    }

    fun reduce(state: AppStateV2.PostDelivery, event: StateEvent): Transition? {
        return when (event) {
            is ScreenUpdateEvent -> {
                val input = event.screenInfo ?: return null
                handleScreenUpdate(state, input)
            }

            is TimeoutEvent -> handleTimeout(state, event)
            else -> null
        }
    }

    private fun handleScreenUpdate(state: AppStateV2.PostDelivery, input: ScreenInfo): Transition? {
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

    private fun handleTimeout(state: AppStateV2.PostDelivery, event: TimeoutEvent): Transition? {
        // If we already succeeded, ignore any lingering timeouts
        if (state.parsedPay != null) return null

        return when (event.type) {
            TimeoutType.SETTLE_UI -> {
                // Timer finished! Fire the click using the freshest node we saved.
                if (state.latestExpandButton != null && state.clickAttempts < MAX_CLICK_ATTEMPTS) {
                    val nextAttempt = state.clickAttempts + 1
                    Timber.d("Settle Timer Expired. Clicking Expand. Attempt $nextAttempt / $MAX_CLICK_ATTEMPTS")

                    Transition(
                        newState = state.copy(
                            clickSent = true,
                            clickAttempts = nextAttempt
                        ),
                        effects = listOf(
                            AppEffect.ClickNode(state.latestExpandButton, "Expand Details"),
                            AppEffect.ScheduleTimeout(
                                GLITCH_RETRY_DELAY,
                                TimeoutType.RETRY_CLICK_TIMEOUT
                            )
                        )
                    )
                } else {
                    null
                }
            }

            TimeoutType.RETRY_CLICK_TIMEOUT -> {
                Timber.w("Expansion verification failed. Resetting loop to retry.")
                // The click failed or the app lagged.
                // Resetting these flags allows the next ScreenUpdateEvent to start the 500ms timer again.
                Transition(
                    state.copy(
                        settleTimerStarted = false,
                        clickSent = false,
                        latestExpandButton = null // Clear stale reference
                    )
                )
            }

            else -> null
        }
    }

    private fun handleDeliverySummary(
        state: AppStateV2.PostDelivery,
        input: ScreenInfo.DeliverySummary
    ): Transition? {
        var newState = state
        val effects = mutableListOf<AppEffect>()

        // Always keep the absolute freshest reference to the button
        if (newState.parsedPay == null && input.expandButton != null) {
            newState = newState.copy(latestExpandButton = input.expandButton)
        }

        // --- PHASE 1: Data Accumulation (The Ground Truth) ---
        if (input.parsedPay != null && input.parsedPay != state.parsedPay) {
            newState = newState.copy(
                parsedPay = input.parsedPay,
                totalPay = input.parsedPay.total,
                summaryText = "Saved: ${UtilityFunctions.formatCurrency(input.parsedPay.total)}"
            )

            // We got the data! Kill the polling loop.
            effects.add(AppEffect.CancelTimeout(TimeoutType.SETTLE_UI))
            effects.add(AppEffect.CancelTimeout(TimeoutType.RETRY_CLICK_TIMEOUT))

            effects.add(
                AppEffect.UpdateBubble(
                    generateReceiptText(input.parsedPay, input.parsedPay.total),
                    ChatPersona.Earnings
                )
            )
            return Transition(newState, effects)
        }

        // Fallback for header total
        if (state.parsedPay == null && input.totalPay > 0 && state.totalPay == 0.0) {
            newState = newState.copy(
                totalPay = input.totalPay,
                summaryText = "Saved: ${UtilityFunctions.formatCurrency(input.totalPay)}"
            )
        }

        // --- PHASE 2: Automation (Start the Settle Timer) ---
        if (newState.parsedPay == null && input.expandButton != null) {
            val canRetry = state.clickAttempts < MAX_CLICK_ATTEMPTS

            // If we haven't started waiting yet, start the 500ms clock.
            if (!newState.settleTimerStarted && !newState.clickSent && canRetry) {
                Timber.d("UI detected. Starting UI Settle Timer ($UI_SETTLE_DELAY ms)")
                newState = newState.copy(settleTimerStarted = true)
                effects.add(AppEffect.ScheduleTimeout(UI_SETTLE_DELAY, TimeoutType.SETTLE_UI))
            }
        }

        return if (newState != state || effects.isNotEmpty()) Transition(
            newState,
            effects
        ) else null
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

        // Safety: Clean up any running timers when leaving the state
        return exitTransition.copy(
            effects = listOf(
                logEffect,
                AppEffect.CancelTimeout(TimeoutType.SETTLE_UI),
                AppEffect.CancelTimeout(TimeoutType.RETRY_CLICK_TIMEOUT)
            ) + exitTransition.effects
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