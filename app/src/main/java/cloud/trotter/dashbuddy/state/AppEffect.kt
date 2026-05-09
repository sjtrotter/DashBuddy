package cloud.trotter.dashbuddy.state

import cloud.trotter.dashbuddy.core.database.event.AppEventEntity
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.state.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.RequestedAction

sealed class AppEffect {

    /**
     * Optional idempotency key. When non-null, the effect is checked against
     * the `effects_fired` table during crash-recovery replay to prevent
     * duplicate execution. Only effects with observable side effects
     * (DB writes, notifications) need a key.
     */
    open val effectKey: String? get() = null

    // 1. Log to Database (The Core of Event Sourcing)
    data class LogEvent(val event: AppEventEntity) : AppEffect() {
        override val effectKey: String get() = "log:${event.eventType}:${event.occurredAt}"
    }

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

    data class ProcessTipNotification(
        val amount: Double,
        val storeName: String,
        val deliveredAt: String,
    ) : AppEffect()

    // Dash Paused!
    data class ScheduleTimeout(val durationMs: Long, val type: TimeoutType) : AppEffect()
    data class CancelTimeout(val type: TimeoutType) : AppEffect()

    data object StartOdometer : AppEffect()
    data object StopOdometer : AppEffect()
    data object PauseOdometer : AppEffect()   // pause GPS while stationary; session total preserved
    data object ResumeOdometer : AppEffect()  // resume GPS after stationary pause
    data class EvaluateOffer(val parsedOffer: ParsedOffer) : AppEffect()
    data class SpeakOffer(val parsedOffer: ParsedOffer, val platformName: String) : AppEffect()

    data class ClickNode(
        val node: UiNode,
        val description: String = "Auto-Click"
    ) : AppEffect()

    /** A rule-originated UI action (ADR-0006). */
    data class RequestAction(val action: RequestedAction) : AppEffect() {
        override val effectKey: String
            get() = "action:${action.ruleId}:${action.dedupeKey ?: action.targetRef.pathFingerprint}"
    }

    data class Delayed(val delayMs: Long, val effect: AppEffect) : AppEffect()
    data class SequentialEffect(
        val effects: List<AppEffect>
    ) : AppEffect()

    data class StartDash(val dashId: String, val platformName: String = "DoorDash") : AppEffect() {
        override val effectKey: String get() = "start_dash:$dashId"
    }
    data class EndDash(val platformName: String? = null) : AppEffect()
}