package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.state.AppEffect
import cloud.trotter.dashbuddy.ui.bubble.BubbleManager // <--- Import
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton // <--- Now an injectable class
class TipEffectHandler @Inject constructor(
    private val bubbleManager: BubbleManager // <--- Injected!
) {

    private val tipPattern = Pattern.compile(
        "added \\$(\\d+\\.\\d{2}) tip on a past (.+) order delivered at (\\d{1,2}/\\d{1,2}), (\\d{1,2}:\\d{2}) ([AP]M)",
        Pattern.CASE_INSENSITIVE
    )

    fun process(scope: CoroutineScope, effect: AppEffect.ProcessTipNotification) {
        scope.launch(Dispatchers.IO) {
            try {
                val matcher = tipPattern.matcher(effect.rawText)
                if (matcher.find()) {
                    val amountStr = matcher.group(1)
                    val storeName = matcher.group(2)

                    Timber.i("Parsed Tip: $$amountStr from $storeName")

                    // Clean Modern Call: No static application reference
                    bubbleManager.postMessage(
                        text = "Nice! $$amountStr tip from $storeName",
                        persona = ChatPersona.Dispatcher
                    )

                } else {
                    Timber.w("Failed to parse tip notification: ${effect.rawText}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing tip notification")
            }
        }
    }
}