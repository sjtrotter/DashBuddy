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
        /** Rule-originated UI actions to execute (ADR-0006). */
        val actions: List<RequestedAction>
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
        override val actions: List<RequestedAction> = emptyList(),
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
        override val actions: List<RequestedAction> = emptyList(),
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
        override val actions: List<RequestedAction> = emptyList(),
    ) : FlowObservation

    /** A timeout fired by the state machine's internal timer system. */
    data class Timeout(
        override val timestamp: Long,
        override val captureId: String? = null,
        override val ruleId: String? = null,
        override val metadata: ReplayMetadata = ReplayMetadata.EMPTY,
        val type: TimeoutType,
    ) : Observation

    /** A UI interaction event from the bubble HUD or overlay. */
    data class UiInput(
        override val timestamp: Long,
        override val captureId: String? = null,
        override val ruleId: String? = null,
        override val metadata: ReplayMetadata = ReplayMetadata.EMPTY,
        val action: String,
        val payload: Map<String, Any?> = emptyMap(),
    ) : Observation

    /** A loopback event from the side-effect engine back into the state machine. */
    data class Loopback(
        override val timestamp: Long,
        override val captureId: String? = null,
        override val ruleId: String? = null,
        override val metadata: ReplayMetadata = ReplayMetadata.EMPTY,
        val effect: String,
        val payload: Map<String, Any?> = emptyMap(),
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
