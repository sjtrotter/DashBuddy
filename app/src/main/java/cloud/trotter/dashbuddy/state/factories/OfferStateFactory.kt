package cloud.trotter.dashbuddy.state.factories

import cloud.trotter.dashbuddy.data.event.AppEventType
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.state.AppStateV2
import cloud.trotter.dashbuddy.state.model.Transition
import cloud.trotter.dashbuddy.state.reducers.ReducerUtils
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OfferStateFactory @Inject constructor() {

    fun createEntry(
        oldState: AppStateV2,
        input: ScreenInfo.Offer,
        isRecovery: Boolean
    ): Transition {
        val merchantName = input.parsedOffer.orders.joinToString(", ") { it.storeName }

        val newState = AppStateV2.OfferPresented(
            dashId = oldState.dashId,
            rawOfferText = input.parsedOffer.rawExtractedTexts,
            merchantName = merchantName,
            amount = input.parsedOffer.payAmount,
            currentOfferHash = input.parsedOffer.offerHash,
            currentScreen = input.screen,
        )

        val effects = mutableListOf<AppEffect>()
        if (!isRecovery) {
            // 1. Log Event
            effects.add(
                AppEffect.LogEvent(
                    ReducerUtils.createEvent(
                        oldState.dashId,
                        AppEventType.OFFER_RECEIVED,
                        input.parsedOffer
                    )
                )
            )

            // 2. Screenshot
            val safeMerchant = merchantName.replace(Regex("[^a-zA-Z0-9 ,.()'-]"), "")
            effects.add(AppEffect.CaptureScreenshot("Offer - $safeMerchant"))

            // 3. Evaluate
            effects.add(AppEffect.EvaluateOffer(input.parsedOffer))
        }

        return Transition(newState, effects)
    }
}