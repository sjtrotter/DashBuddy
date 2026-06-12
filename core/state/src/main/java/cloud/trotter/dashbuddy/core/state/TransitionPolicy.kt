package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.TransitionKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Policy governing screen-authoritative mode transitions.
 *
 * Replaces [HealingPolicy]. Key differences:
 * - Screens apply mode **immediately** — no threshold counting.
 * - Clicks record user intent — they do NOT drive mode.
 * - [classify] is diagnostic only — it never blocks a transition.
 * - Session grace period protects sessions from brief offline blips.
 */
@Singleton
class TransitionPolicy @Inject constructor() {

    companion object {
        const val DEFAULT_GRACE_MS = 10_000L

        /**
         * Short grace for destructive transitions armed by an authoritative-
         * looking signal (the dash-summary screen, #431). Long enough for a
         * contradicting task-flow frame to land and cancel a misrecognition;
         * short enough that real session ends commit promptly.
         */
        const val AUTHORITATIVE_GRACE_MS = 2_500L
    }

    val gracePeriodMs: Long = DEFAULT_GRACE_MS

    val authoritativeGraceMs: Long = AUTHORITATIVE_GRACE_MS

    /**
     * Determine what mode a flow + modeHint combination implies.
     * Returns null if no mode signal is present.
     *
     * Same logic as the former [HealingPolicy.resolveImpliedMode].
     */
    fun resolveMode(flow: Flow?, modeHint: Mode?): Mode? {
        // Explicit hint always wins
        if (modeHint != null) return modeHint

        // Infer from flow
        return when (flow) {
            Flow.OfferPresented,
            Flow.TaskPickupNavigation,
            Flow.TaskPickupArrived,
            Flow.TaskDropoffNavigation,
            Flow.TaskDropoffArrived,
            Flow.PostTask -> Mode.Online

            Flow.SessionEnded -> Mode.Offline
            Flow.Idle -> null // Idle is ambiguous — could be offline or between offers
            null -> null
        }
    }

    /**
     * Classify a transition for logging and lifecycle decisions.
     *
     * When [prevOutcomes] is null (rule declared no outcomes), mode changes
     * default to [TransitionKind.Expected] for backward compatibility.
     */
    fun classify(
        prevMode: Mode,
        impliedMode: Mode?,
        prevOutcomes: Set<Flow>?,
        obs: Observation.FlowObservation,
    ): TransitionKind {
        if (impliedMode == null) return TransitionKind.NoSignal
        if (impliedMode == prevMode) return TransitionKind.Confirmed

        // Mode is changing. Check if the new flow was expected.
        if (prevOutcomes == null) return TransitionKind.Expected // no outcomes declared — compat

        val obsFlow = obs.flow ?: return TransitionKind.Expected // no flow to check against
        return if (obsFlow in prevOutcomes) TransitionKind.Expected else TransitionKind.Unexpected
    }
}
