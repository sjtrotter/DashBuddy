package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.core.state.AppEffect
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TipEffectHandler @Inject constructor(
    private val bubbleManager: BubbleManager
) {

    fun process(scope: CoroutineScope, effect: AppEffect.ProcessTipNotification) {
        scope.launch(Dispatchers.IO) {
            try {
                // #551 P7: the tip amount is the dasher's own economics (INFO-safe); the store name
                // is raw third-party UI text, so it stays on the DEBUG firehose.
                Timber.tag("Effects").i("Tip received: %s", Formats.money(effect.amount))
                Timber.tag("Effects").d(
                    "Tip received: %s from %s", Formats.money(effect.amount), effect.storeName,
                )
                bubbleManager.postMessage(
                    text = "Nice! ${Formats.money(effect.amount)} tip from ${effect.storeName}",
                    persona = ChatPersona.Dispatcher
                )
            } catch (e: Exception) {
                Timber.tag("Effects").e(e, "Error processing tip notification")
            }
        }
    }
}
