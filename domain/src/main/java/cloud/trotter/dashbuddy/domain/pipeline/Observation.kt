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
 * The `platform` for any observation is derived from [ruleId] via
 * [Platform.fromRuleId].
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
        /** Per-trigger overrides that replace built-in transition defaults. */
        val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>>
        /** Plausible next flows declared by the matched rule's `outcomes` field. */
        val expectedOutcomes: Set<Flow>?
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
        override val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>> = emptyMap(),
        override val expectedOutcomes: Set<Flow>? = null,
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
        override val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>> = emptyMap(),
        override val expectedOutcomes: Set<Flow>? = null,
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
        override val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>> = emptyMap(),
        override val expectedOutcomes: Set<Flow>? = null,
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
    ) : Observation

    /** A loopback event from the side-effect engine back into the state machine. */
    data class Loopback(
        override val timestamp: Long,
        override val captureId: String? = null,
        override val ruleId: String? = null,
        override val metadata: ReplayMetadata = ReplayMetadata.EMPTY,
        val effect: String,
        /** Typed loopback context (#366) — e.g. the landed offer evaluation. */
        val payload: ObservationPayload? = null,
    ) : Observation
}

/**
 * Timeout types used by the state machine's internal timer system.
 */
enum class TimeoutType {
    SESSION_PAUSED_SAFETY,
    RETRY_CLICK,
    SETTLE_UI,
    DECLINE_POPUP_WAIT,
    SCREENSHOT_WAIT,
}
