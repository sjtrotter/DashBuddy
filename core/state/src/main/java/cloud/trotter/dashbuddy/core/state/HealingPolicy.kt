package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ModeConfidence
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verdict from mode-inference plausibility check.
 */
enum class Verdict {
    /** Implied mode equals current mode — no transition needed. */
    NoChange,
    /** Transition is plausible — apply immediately. */
    PlausibleApply,
    /** Transition is implausible — accrue confidence before applying. */
    Implausible,
}

/**
 * Result of inferring mode from a flow observation.
 */
data class ModeInference(
    val impliedMode: Mode?,
    val verdict: Verdict,
)

/**
 * Policy governing mode-transition plausibility and healing thresholds.
 *
 * Default: 2 supporting observations within 10s, OR 1 high-weight signal
 * (explicit mode-defining screen like an offer or pause screen).
 */
@Singleton
class HealingPolicy @Inject constructor() {

    val observationThreshold: Int = ModeConfidence.DEFAULT_OBSERVATION_THRESHOLD
    val timeWindowMs: Long = ModeConfidence.DEFAULT_TIME_WINDOW_MS

    /**
     * Infer what mode a flow + modeHint combination implies, then check
     * plausibility against the current mode.
     */
    fun inferAndCheck(currentMode: Mode, flow: Flow?, modeHint: Mode?): ModeInference {
        val implied = resolveImpliedMode(flow, modeHint) ?: return ModeInference(null, Verdict.NoChange)
        if (implied == currentMode) return ModeInference(implied, Verdict.NoChange)
        return if (isPlausible(currentMode, implied, flow)) {
            ModeInference(implied, Verdict.PlausibleApply)
        } else {
            ModeInference(implied, Verdict.Implausible)
        }
    }

    /**
     * Returns the healing threshold for a given mode transition direction.
     *
     * Offline → Online uses a lower threshold (1) because:
     * - With dedup-by-classification, repeated identical screens don't re-send
     * - Scheduled dashes may only produce one online signal (waiting_for_offer)
     * - False positive cost is low (bubble starts early, user dismisses)
     *
     * All other transitions use the default threshold (2) because:
     * - Online → Offline false positives are costly (session ends prematurely)
     * - Back-gesture flicker can briefly show offline screens
     * - Normal end-dash flow has abundant signals (end_dash click, session_ended, idle_map)
     */
    fun thresholdFor(currentMode: Mode, impliedMode: Mode): Int = when {
        currentMode == Mode.Offline && impliedMode == Mode.Online -> 1
        else -> observationThreshold
    }

    /**
     * Checks whether accrued confidence meets the threshold for healing.
     *
     * Stale confidence (older than [timeWindowMs]) is ignored — the caller
     * should reset confidence when the window expires.
     */
    fun shouldHeal(confidence: ModeConfidence, now: Long, threshold: Int): Boolean {
        // Discard stale confidence: if the first observation is older than
        // the time window, the accrued evidence is no longer meaningful.
        val firstSeen = confidence.firstSeenAt ?: return false
        if (now - firstSeen > timeWindowMs) return false

        return confidence.supportingObservations >= threshold
    }

    /**
     * Determine what mode a flow observation implies.
     * Returns null if no mode signal is present.
     */
    private fun resolveImpliedMode(flow: Flow?, modeHint: Mode?): Mode? {
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
     * Determines whether a mode transition is plausible given the current
     * state. Plausible transitions apply immediately; implausible ones
     * require healing (confidence accrual).
     */
    private fun isPlausible(currentMode: Mode, impliedMode: Mode, flow: Flow?): Boolean {
        return when (currentMode) {
            Mode.Online -> when (impliedMode) {
                Mode.Paused -> true  // Online → Paused: explicit pause screen
                Mode.Offline -> flow == Flow.SessionEnded // Only via explicit session end
                Mode.Online -> true  // no-op
            }
            Mode.Paused -> when (impliedMode) {
                Mode.Online -> true  // Paused → Online: any active screen
                Mode.Offline -> true // Paused → Offline: session timeout/end
                Mode.Paused -> true  // no-op
            }
            Mode.Offline -> when (impliedMode) {
                // Offline → Online: always implausible — might be app restart mid-task
                // or back-gesture flicker. Require healing.
                Mode.Online -> false
                Mode.Paused -> false // shouldn't happen, but treat as implausible
                Mode.Offline -> true // no-op
            }
        }
    }
}
