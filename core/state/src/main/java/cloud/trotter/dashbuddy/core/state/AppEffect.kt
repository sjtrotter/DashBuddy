package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect

sealed class AppEffect {

    /**
     * Optional idempotency key. When non-null, the effect is checked against
     * the `effects_fired` table during crash-recovery replay to prevent
     * duplicate execution. Only effects with observable side effects
     * (DB writes, notifications) need a key.
     */
    open val effectKey: String? get() = null

    /**
     * Log a domain [AppEvent] (the core of event sourcing). Entity assembly —
     * payload encoding + device metadata — happens at the executor edge (#354/#119);
     * the state layer emits pure domain data. `occurredAt` is observation-derived,
     * so this key is identical between live execution and recovery replay (#300).
     */
    data class LogEvent(val event: AppEvent) : AppEffect() {
        override val effectKey: String get() = "log:${event.type}:${event.occurredAt}"
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
    data class ScheduleTimeout(
        val durationMs: Long,
        val type: TimeoutType,
        /**
         * Platform region this timer belongs to — threaded through TimeoutEvent →
         * Observation.Timeout so the fire routes back to the owning region instead
         * of Platform.Unknown (#342). Null = not platform-scoped.
         */
        val platform: Platform? = null,
        /**
         * Opaque payload carried through the timer back into [Observation.Timeout].
         * Used to thread effect context (e.g. click target NodeRef fields) through
         * the UDF round-trip when the firing of a downstream effect must be deferred.
         */
        val payload: Map<String, Any?> = emptyMap(),
    ) : AppEffect()
    data class CancelTimeout(val type: TimeoutType) : AppEffect()

    data object StartOdometer : AppEffect()
    data object StopOdometer : AppEffect()
    data object PauseOdometer : AppEffect()   // pause GPS while stationary; session total preserved
    data object ResumeOdometer : AppEffect()  // resume GPS after stationary pause
    /**
     * Evaluate the pending offer. [offerHash] rides the async round-trip so the result
     * can be correlated back — a replaced offer must never inherit the previous offer's
     * evaluation (#345).
     */
    data class EvaluateOffer(val parsedOffer: ParsedOffer, val offerHash: String) : AppEffect()
    /** Speak the offer's evaluation aloud (verdict + headline economics). Fires on eval-landing. */
    data class SpeakOffer(val evaluation: OfferEvaluation) : AppEffect()

    /**
     * Post the offer evaluation as a heads-up notification with Accept/Decline actions. Emitted by
     * [EffectMap] when the async evaluation lands on the pending offer (eval null → non-null) — not
     * fired inline from the [EvaluateOffer] loopback handler — so the offer's UI side-effects stay
     * first-class and testable. The app layer formats the summary + persona from the evaluation.
     */
    data class PostOfferNotification(val evaluation: OfferEvaluation) : AppEffect()

    data class ClickNode(
        val node: UiNode,
        val description: String = "Auto-Click"
    ) : AppEffect()

    /**
     * HUD-initiated offer action (bubble Accept/Decline) → perform the platform's offer
     * click. Platform-agnostic; the app layer resolves the concrete node (see #85 GigPlatform).
     */
    data class PerformOfferAction(
        val action: OfferAction,
        val platform: Platform,
    ) : AppEffect()

    /** A rule-originated side effect. */
    data class RequestEffect(val effect: RequestedEffect) : AppEffect() {
        override val effectKey: String
            get() = "effect:${effect.ruleId}:${effect.dedupeKey ?: effect.targetRef?.pathFingerprint ?: effect.verb.wire}"
    }

    data class SequentialEffect(
        val effects: List<AppEffect>
    ) : AppEffect()

    data class StartSession(val sessionId: String, val platformName: String) : AppEffect() {
        override val effectKey: String get() = "start_session:$sessionId"
    }
    data class EndSession(val platformName: String? = null) : AppEffect()
}