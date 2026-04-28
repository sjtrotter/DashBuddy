package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.state.AppEffect
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
                Timber.i("Tip received: \$${effect.amount} from ${effect.storeName}")
                bubbleManager.postMessage(
                    text = "Nice! \$${effect.amount} tip from ${effect.storeName}",
                    persona = ChatPersona.Dispatcher
                )
            } catch (e: Exception) {
                Timber.e(e, "Error processing tip notification")
            }
        }
    }
}
