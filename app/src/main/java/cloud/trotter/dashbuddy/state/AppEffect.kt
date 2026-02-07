package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.data.event.AppEventEntity
import cloud.trotter.dashbuddy.data.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.chat.ChatPersona
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.state.model.TimeoutType

sealed class AppEffect {
    // 1. Log to Database (The Core of Event Sourcing)
    data class LogEvent(val event: AppEventEntity) : AppEffect()

    // 2. Update UI (The Bubble)
    data class UpdateBubble(
        val text: String,
        val persona: ChatPersona = ChatPersona.Dispatcher,
        val expand: Boolean = false
    ) : AppEffect()

    // 3. System Commands (e.g. "Keep Screen On", "Play Sound")
    data object PlayNotificationSound : AppEffect()

    data class CaptureScreenshot(
        val filenamePrefix: String, // e.g. "offer" or "payout"
        val metadata: String? = null // Optional context
    ) : AppEffect()

    data class ProcessTipNotification(val rawText: String) : AppEffect()

    // Dash Paused!
    data class ScheduleTimeout(val durationMs: Long, val type: TimeoutType) : AppEffect()
    data class CancelTimeout(val type: TimeoutType) : AppEffect()

    data object StartOdometer : AppEffect()
    data object StopOdometer : AppEffect()
    data class EvaluateOffer(val parsedOffer: ParsedOffer) : AppEffect()

    data class ClickNode(
        val node: UiNode,
        val description: String = "Auto-Click"
    ) : AppEffect()

    data class Delayed(val delayMs: Long, val effect: AppEffect) : AppEffect()
    data class SequentialEffect(
        val effects: List<AppEffect>
    ) : AppEffect()

    data class StartDash(val dashId: String) : AppEffect()
    data object EndDash : AppEffect()
}