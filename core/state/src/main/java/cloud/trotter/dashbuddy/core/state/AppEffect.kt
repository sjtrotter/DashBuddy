package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.action.ActionTrigger
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.config.EvidenceCategory
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.model.chat.ChatPersona
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload

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
     * so the default key is identical between live execution and recovery replay (#300).
     *
     * [effectKeyOverride] lets a caller scope idempotency to a logical identity instead of
     * the observation timestamp — a once-per-thing event whose triggering screen can recur.
     * #518: DELIVERY_COMPLETED keys on the completed task so a re-entered PostTask receipt
     * (PostTask→nav→PostTask→nav) can't fire — and double-count — the same delivery twice
     * (06-17 db: real $23.62 and $11.20 deliveries each logged twice on a receipt flap). The
     * override must itself be replay-stable (taskId is minted deterministically).
     */
    data class LogEvent(
        val event: AppEvent,
        val effectKeyOverride: String? = null,
    ) : AppEffect() {
        override val effectKey: String get() = effectKeyOverride ?: "log:${event.type}:${event.occurredAt}"
    }

    // 2. Update UI (The Bubble)
    //
    // #566: per-task bubbles (a pickup/dropoff heads-up) carry a [dedupeScope] = the task id, so the
    // engine's effects_fired idempotency gate (which only acts on a non-null effectKey) collapses the
    // double fly-away that fired when two EffectMap sites emitted the same "Pickup: <store>" on
    // consecutive frames of one leg. The key is scoped to task + persona + content so it does NOT
    // suppress a legitimate re-emit: the same task switching persona (NAVIGATOR→SHOPPER) or a later,
    // distinct leg at the same store both produce a different key and still fire. One-shot bubbles
    // (offer/session/resume/paused/earnings) leave [dedupeScope] null → effectKey null → never deduped
    // (they may legitimately recur within the effects_fired window). persona.id for a Customer is the
    // store-flavored label ("<store>'s customer", #568), never raw customer text; merchant names
    // aren't PII; text.hashCode() avoids storing the literal in the table. NOTE: this reuses the
    // recovery-scoped effects_fired table for a cosmetic UI
    // dedup rather than the in-state lastAnnouncedPostTaskTaskId anchor — a deliberate, lighter trade
    // for a LOW cosmetic bug (UpdateBubble is an external effect, suppressed at the recovery gate
    // before markFired, so recovery replays are unaffected).
    data class UpdateBubble(
        val text: String,
        val persona: ChatPersona = ChatPersona.Dispatcher,
        val expand: Boolean = false,
        val dedupeScope: String? = null,
    ) : AppEffect() {
        override val effectKey: String? get() =
            dedupeScope?.let { "bubble:$it:${persona.id}:${text.hashCode()}" }
    }

    /**
     * Capture an evidence screenshot. [category] is the user-consent bucket
     * the engine's evidence gate (#426) checks against `EvidenceConfig` —
     * null (an effect that never declared one) never fires.
     */
    data class CaptureScreenshot(
        val filenamePrefix: String, // e.g. "offer" or "payout"
        val category: EvidenceCategory?,
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
         * Typed payload carried through the timer back into [Observation.Timeout]
         * (#366) — e.g. the deferred-click context when a downstream effect's
         * firing must wait for the UI to settle.
         */
        val payload: ObservationPayload? = null,
    ) : AppEffect()
    data class CancelTimeout(
        val type: TimeoutType,
        /**
         * Platform region whose timer of [type] to cancel (#438 item 1) — MUST match
         * the [ScheduleTimeout.platform] of the timer being cancelled. The engine keys
         * its timer registry by (type, platform), so two paused platforms hold their own
         * SESSION_PAUSED_SAFETY timer and one platform's resume-cancel no longer kills the
         * other's. Null = the non-platform-scoped timer of this type.
         */
        val platform: Platform? = null,
    ) : AppEffect()

    data object StartOdometer : AppEffect()
    data object StopOdometer : AppEffect()
    data object PauseOdometer : AppEffect()   // pause GPS while stationary; session total preserved
    data object ResumeOdometer : AppEffect()  // resume GPS after stationary pause

    /**
     * #556: a Shop & Deliver pickup just completed — fold its measured pace ([itemsShopped] over
     * [shopDurationMs] in-store) into the learned overall items/min. Idempotent per task so a
     * re-observed confirm can't double-count (mirrors the #518 DELIVERY_COMPLETED keying).
     */
    data class RecordShopRate(
        val platform: Platform,
        val itemsShopped: Int,
        val shopDurationMs: Long,
        val storeName: String?,
        val jobId: String,
        val taskId: String,
    ) : AppEffect() {
        // #588: platform-namespaced dedup key — a per-platform learned rate; the taskId is
        // per-platform-unique already, the wire prefix keeps the namespace disjoint (mirrors #438-B4).
        override val effectKey: String get() = "shop_rate:${platform.wire}:$taskId"
    }

    /**
     * #823 Phase 1: a units-denominated Shop & Deliver pickup just completed — fold its measured
     * items:units ratio ([itemsShopped] actually shopped vs the offer's quoted [offerUnitCount]) into
     * the learned per-platform ratio. Emitted alongside [RecordShopRate] only when the job's accepted
     * offer was units-denominated. Idempotent per task (same keying discipline).
     */
    data class RecordItemsPerUnitRatio(
        val platform: Platform,
        val offerUnitCount: Int,
        val itemsShopped: Int,
        val jobId: String,
        val taskId: String,
    ) : AppEffect() {
        override val effectKey: String get() = "items_per_unit_ratio:${platform.wire}:$taskId"
    }
    /**
     * Evaluate the pending offer. [offerHash] rides the async round-trip so the result
     * can be correlated back — a replaced offer must never inherit the previous offer's
     * evaluation (#345). [platform] is the offer's own provenance (derived from its
     * `sourceRuleId`, #438 item 8a) so the eval loopback can stamp identity onto the
     * result — an identity-less loopback lands on no region post-#682.
     */
    data class EvaluateOffer(
        val parsedOffer: ParsedOffer,
        val offerHash: String,
        val platform: Platform,
    ) : AppEffect()
    /** Speak the offer's evaluation aloud (verdict + headline economics). Fires on eval-landing. */
    data class SpeakOffer(val evaluation: OfferEvaluation) : AppEffect()

    /**
     * Post the offer evaluation as a heads-up notification with Accept/Decline actions. Emitted by
     * [EffectMap] when the async evaluation lands on the pending offer (eval null → non-null) — not
     * fired inline from the [EvaluateOffer] loopback handler — so the offer's UI side-effects stay
     * first-class and testable. The app layer formats the summary + persona from the evaluation.
     */
    data class PostOfferNotification(
        val evaluation: OfferEvaluation,
        /**
         * #578: the rich offer snapshot (badges, score, $/hr/$/mi, expiry anchors) the heads-up
         * notification renders as a mini offer card — the SAME [FlowCardSnapshot.Offer] the bubble
         * card uses, so the two can't drift. [evaluation] is still carried for the chat summary +
         * persona + the BigText fallback.
         */
        val offer: FlowCardSnapshot.Offer,
        /** Keys the engine's delayed post so [CancelOfferNotification] can abort it (#436). */
        val offerHash: String?,
        /**
         * The offer's platform (#438 item 8a), derived from its `sourceRuleId`. Rides the
         * notification's Accept/Decline PendingIntent extras so the dispatched [UiInput]
         * carries a real target platform instead of deriving [Platform.Unknown].
         */
        val platform: Platform,
    ) : AppEffect()

    /**
     * Abort a pending (not-yet-posted) offer notification (#436). Emitted by
     * [EffectMap] when the offer resolves (accept/decline/timeout): the post
     * is delayed ~750ms behind the screenshot settle, so without this an
     * actionable Accept/Decline heads-up could land AFTER the offer was
     * already gone.
     */
    data class CancelOfferNotification(val offerHash: String?) : AppEffect()

    /**
     * Perform an app-owned [RuleAction] on the platform app (#425) — the only
     * path by which DashBuddy ever taps a third-party UI. [targetRef] is the
     * ruleset-bound target (recognition data); the executor re-resolves it
     * against the live tree scoped to the platform's package and verifies the
     * node against the action's app-owned expectation before clicking.
     * [sourceRuleId] is the rule that supplied the binding — consent-gate
     * provenance (#422/#417). [trigger] records who initiated the fire: an
     * AUTOMATION fire must be covered by a granted capability at the engine's
     * consent gate, while a USER fire is its own consent (#417); integrity
     * checks (tier, package, label, throttle) apply to both.
     */
    data class PerformRuleAction(
        val action: RuleAction,
        val platform: Platform,
        val targetRef: NodeRef,
        val sourceRuleId: String?,
        val trigger: ActionTrigger,
    ) : AppEffect()

    /**
     * A rule-originated side effect.
     *
     * [keySuffix] disambiguates otherwise-identical effects across discrete
     * occurrences of the same rule (#604): a notification's rule-declared
     * effects have no per-arrival identity of their own (no dedupeKey), so
     * without a suffix every arrival of e.g. `doordash.notification.new_order`
     * collapses onto one global `effects_fired` key — the first notification
     * fires it, every later one silently no-ops as "already fired". Screen
     * observations pass `null` here: their cross-frame dedup (e.g.
     * `offer-ss-{parsedHash}`) is intended and must NOT be suffixed.
     */
    data class RequestEffect(
        val effect: RequestedEffect,
        val keySuffix: String? = null,
    ) : AppEffect() {
        override val effectKey: String
            get() = "effect:${effect.ruleId}:${effect.dedupeKey ?: effect.verb.wire}" +
                keySuffix?.let { ":$it" }.orEmpty()
    }

    data class StartSession(val sessionId: String, val platformName: String) : AppEffect() {
        override val effectKey: String get() = "start_session:$sessionId"
    }
    data class EndSession(val platformName: String? = null) : AppEffect()
}