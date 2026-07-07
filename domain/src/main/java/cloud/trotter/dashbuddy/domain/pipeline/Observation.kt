package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * The unified event type that the pipeline produces and the state machine
 * consumes. Extends [StateEvent] so it can flow through the existing
 * state machine merge alongside engine events.
 *
 * The `platform` for an observation defaults to [Platform.fromRuleId] on
 * [ruleId]; subtypes that receive no ruleId ([Timeout], [UiInput], [Loopback])
 * carry an explicit `targetPlatform` that takes precedence (#342 / #438 8a).
 */
sealed interface Observation : cloud.trotter.dashbuddy.domain.model.state.StateEvent {
    override val timestamp: Long
    val captureId: String?
    val ruleId: String?
    val metadata: ReplayMetadata

    /** Derived platform from [ruleId]. */
    val platform: Platform get() = Platform.fromRuleId(ruleId)

    /**
     * An observation that carries flow/mode/parsed-field state contributions.
     * This is the IR boundary between rules (Layer 2) and the state machine
     * (Layer 3).
     */
    sealed interface FlowObservation : Observation {
        val flow: Flow?
        val modeHint: Mode?
        val parsed: ParsedFields
        /** Platform-specific screen/event identity for debugging and bridging. */
        val target: String?
        /** Rule-originated side effects to execute. */
        val effects: List<RequestedEffect>
        /**
         * Named UI targets the matched rule's `bind` block resolved on this
         * screen (#425) — e.g. `"declineButton"` → the decline button's
         * [NodeRef]. Recognition-layer *data*; the app-owned `RuleAction`
         * registry decides whether anything is ever done with them.
         */
        val targets: Map<String, NodeRef>
        /** Per-trigger overrides that replace built-in transition defaults. */
        val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>>
    }

    /** A screen classification from the accessibility window pipeline. */
    data class Screen(
        override val timestamp: Long,
        override val captureId: String?,
        override val ruleId: String?,
        override val metadata: ReplayMetadata,
        override val flow: Flow?,
        override val modeHint: Mode?,
        override val parsed: ParsedFields,
        override val target: String? = null,
        override val effects: List<RequestedEffect> = emptyList(),
        override val targets: Map<String, NodeRef> = emptyMap(),
        override val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>> = emptyMap(),
    ) : FlowObservation

    /** A click/tap event classified by the click pipeline. */
    data class Click(
        override val timestamp: Long,
        override val captureId: String?,
        override val ruleId: String?,
        override val metadata: ReplayMetadata,
        override val flow: Flow?,
        override val modeHint: Mode?,
        override val parsed: ParsedFields,
        override val target: String? = null,
        override val effects: List<RequestedEffect> = emptyList(),
        override val targets: Map<String, NodeRef> = emptyMap(),
        override val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>> = emptyMap(),
        /** The last classified screen target when this click occurred. */
        val screenTarget: String? = null,
    ) : FlowObservation

    /** A notification event classified by the notification pipeline. */
    data class Notification(
        override val timestamp: Long,
        override val captureId: String?,
        override val ruleId: String?,
        override val metadata: ReplayMetadata,
        override val flow: Flow?,
        override val modeHint: Mode?,
        override val parsed: ParsedFields,
        override val target: String? = null,
        override val effects: List<RequestedEffect> = emptyList(),
        override val targets: Map<String, NodeRef> = emptyMap(),
        override val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>> = emptyMap(),
    ) : FlowObservation

    /** A timeout fired by the state machine's internal timer system. */
    data class Timeout(
        override val timestamp: Long,
        override val captureId: String? = null,
        override val ruleId: String? = null,
        override val metadata: ReplayMetadata = ReplayMetadata.EMPTY,
        val type: TimeoutType,
        /**
         * The platform region this timer belongs to. Timeouts carry no ruleId, so
         * without an explicit target they derive [Platform.Unknown] and the owning
         * region never sees the fire — the pause-safety timer was dead (#342).
         * Null = not platform-scoped.
         */
        val targetPlatform: Platform? = null,
        /**
         * Typed context from the original ScheduleTimeout (#366) — e.g. the
         * deferred-click target for click-after-settle. Serializable, so the
         * journal replays it losslessly.
         */
        val payload: ObservationPayload? = null,
    ) : Observation {
        override val platform: Platform get() = targetPlatform ?: Platform.fromRuleId(ruleId)
    }

    /** A UI interaction event from the bubble HUD or overlay. */
    data class UiInput(
        override val timestamp: Long,
        override val captureId: String? = null,
        override val ruleId: String? = null,
        override val metadata: ReplayMetadata = ReplayMetadata.EMPTY,
        val action: String,
        /**
         * The platform the offer being acted on belongs to (#438 item 8a). Like
         * [Timeout.targetPlatform] (#342), a UiInput carries no ruleId, so without an
         * explicit target it derives [Platform.Unknown] and post-#682 never steps any
         * region — an identity-less accept/decline would land nowhere. The bubble/
         * notification dispatch stamps the acted offer's platform. Null = not scoped.
         */
        val targetPlatform: Platform? = null,
        /** The offer this input targets (#438 item 8a) — correlates the tap to its offer. */
        val offerHash: String? = null,
    ) : Observation {
        override val platform: Platform get() = targetPlatform ?: Platform.fromRuleId(ruleId)
    }

    /** A loopback event from the side-effect engine back into the state machine. */
    data class Loopback(
        override val timestamp: Long,
        override val captureId: String? = null,
        override val ruleId: String? = null,
        override val metadata: ReplayMetadata = ReplayMetadata.EMPTY,
        val effect: String,
        /**
         * The platform the looped-back offer belongs to (#438 item 8a) — same
         * identity-less-observation problem as [UiInput.targetPlatform]: the eval
         * loopback carries no ruleId, so the owning region never sees it. Stamped
         * from the [AppEffect.EvaluateOffer]'s effect-carried platform. Null = not scoped.
         */
        val targetPlatform: Platform? = null,
        /** Typed loopback context (#366) — e.g. the landed offer evaluation. */
        val payload: ObservationPayload? = null,
    ) : Observation {
        override val platform: Platform get() = targetPlatform ?: Platform.fromRuleId(ruleId)

        companion object {
            /** Effect token for the async offer-evaluation loopback (#402). */
            const val EFFECT_OFFER_EVALUATED = "offer_evaluated"
        }
    }
}

/**
 * Timeout types used by the state machine's internal timer system.
 */
enum class TimeoutType {
    SESSION_PAUSED_SAFETY,
    SETTLE_UI,

    /**
     * Wakes the state machine when a [PendingDestructive] grace deadline
     * lapses (#431), so commits fire on time instead of waiting for the next
     * observation. Carries no handler logic of its own: the stepper's lazy
     * expiry (which runs before timeout handling) performs the commit.
     */
    GRACE_COMMIT,

    /**
     * Wakes the state machine when a `PendingModeResume` grace deadline lapses
     * (#605), committing a graced screen-implied resume out of [Mode.Paused].
     * Like [GRACE_COMMIT] it carries no handler logic — the stepper's lazy
     * expiry performs the mode flip. A SEPARATE type (not [GRACE_COMMIT] reuse)
     * is REQUIRED because both graces belong to the SAME platform region, so even
     * the (type, platform) timer key (#438 item 1) would not separate them: sharing
     * [GRACE_COMMIT] would cross-cancel a live `TASK_RETIRE`/`SESSION_END` grace
     * timer (re-opening #431).
     */
    MODE_RESUME_COMMIT,

    /**
     * Expires a presented offer whose overlay can vanish without emitting a frame (#438 B3 /
     * vet H1). Armed by EffectMap on offer push ([cloud.trotter.dashbuddy.core.state.OfferEffects],
     * the [GRACE_COMMIT] mechanism — NEVER inside a reducer), deadline `presentedAt +
     * countdown*1000` else a 120s de-facto TTL; cancelled on resolution. The fire carries the
     * offer's `offerHash` in its [Observation.Timeout.payload] and resolves BY hash, and NO-OPS on
     * an accept-latched / accepted-pending-consumption offer — both TTLs land inside the accept
     * grace, and timing-out an accepted survivor would log a false `OFFER_TIMEOUT` and destroy the
     * mint (the #526 regression the survival rule prevents).
     */
    OFFER_EXPIRY,
}
