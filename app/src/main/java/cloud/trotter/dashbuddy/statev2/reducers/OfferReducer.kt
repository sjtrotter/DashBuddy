package cloud.trotter.dashbuddy.statev2.reducers

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.statev2.AppEffect
import cloud.trotter.dashbuddy.statev2.AppStateV2
import cloud.trotter.dashbuddy.statev2.Reducer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OfferReducer {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun transitionTo(
        oldState: AppStateV2,
        input: ScreenInfo.Offer,
        isRecovery: Boolean
    ): Reducer.Transition {
        val merchantName = input.parsedOffer.orders.joinToString(", ") { it.storeName }

        val newState = AppStateV2.OfferPresented(
            dashId = oldState.dashId,
            rawOfferText = input.parsedOffer.rawExtractedTexts,
            merchantName = merchantName,
            amount = input.parsedOffer.payAmount
        )

        val effects = mutableListOf<AppEffect>()
        if (!isRecovery) {
            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        oldState.dashId,
                        AppEventType.OFFER_RECEIVED,
                        input.parsedOffer
                    )
                )
            )

            // --- NEW: Custom Filename ---
            val date = dateFormat.format(Date())
            // Sanitize merchant name for filename (remove slashes, colons, etc)
            val safeMerchant = merchantName.replace(Regex("[^a-zA-Z0-9 ,.()'-]"), "")
            val filename = "$date - Offer - $safeMerchant"

            effects.add(AppEffect.CaptureScreenshot(filename))
            // ----------------------------
        }

        return Reducer.Transition(newState, effects)
    }

    // ... (reduce function remains same) ...
    fun reduce(state: AppStateV2.OfferPresented, input: ScreenInfo): Reducer.Transition? {
        return when (input) {
            is ScreenInfo.Offer -> Reducer.Transition(state)
            is ScreenInfo.PickupDetails -> PickupReducer.transitionTo(
                state,
                input,
                isRecovery = false
            )

            is ScreenInfo.WaitingForOffer -> {
                val transition = AwaitingReducer.transitionTo(state, input, isRecovery = false)
                val declineEvent = ReducerUtils.createEvent(
                    state.dashId,
                    AppEventType.OFFER_DECLINED,
                    "Returned to search"
                )
                transition.copy(effects = transition.effects + AppEffect.LogEvent(declineEvent))
            }

            is ScreenInfo.DashPaused -> {
                DashPausedReducer.transitionTo(state, input, isRecovery = false)
            }

            else -> null
        }
    }
}