package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.settings.GraceConfigProvider
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.Platform
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Policy governing screen-authoritative mode transitions.
 *
 * Replaces [HealingPolicy]. Key differences:
 * - Screens apply mode **immediately** — no threshold counting.
 * - Clicks record user intent — they do NOT drive mode.
 * - Session grace period protects sessions from brief offline blips.
 */
@Singleton
class TransitionPolicy @Inject constructor(
    /**
     * Per-platform grace/timing snapshot (#438 item 6, vet M7). Injected as an
     * eagerly-materialized synchronous value provider — the `evidenceConfig.value`
     * pattern — read ONCE per step-driven accessor call, never collected inside
     * a reducer. Defaults to [GraceConfigProvider.Defaults] (code constants) when
     * unbound, so the pure steppers and every test's `TransitionPolicy()` behave
     * exactly as the former compile-time constants.
     *
     * Replay-determinism tradeoff (a grace edited between a live run and its
     * crash replay changes replayed commit timing) is pre-accepted — see
     * [GraceConfigProvider] / the #438 design doc §B6.
     */
    private val graceConfig: GraceConfigProvider,
) {

    /** Test/default convenience — code-constant timing for every platform. */
    constructor() : this(GraceConfigProvider.Defaults)

    companion object {
        // Re-exported from [GraceConfig] (the SSOT) for existing test/comment refs.
        const val DEFAULT_GRACE_MS = GraceConfig.DEFAULT_GRACE_MS

        /**
         * Short grace for destructive transitions armed by an authoritative-
         * looking signal (the dash-summary screen, #431). Long enough for a
         * contradicting task-flow frame to land and cancel a misrecognition;
         * short enough that real session ends commit promptly.
         */
        const val AUTHORITATIVE_GRACE_MS = GraceConfig.AUTHORITATIVE_GRACE_MS

        /**
         * Grace for a screen-implied resume out of [Mode.Paused] (#605). Must
         * exceed the observed ~4.3s under-modal receipt-flap window with margin
         * (DoorDash's pause sheet sits on the just-completed delivery summary,
         * so frames flap paused ↔ online) yet stay short enough that a real
         * resume's card/log lands promptly — the resume is not glance-critical,
         * so a ≤8s lag after the dasher taps Resume is acceptable.
         */
        const val PAUSE_RESUME_GRACE_MS = GraceConfig.PAUSE_RESUME_GRACE_MS
    }

    /** Provisional-destructive commit grace for [platform] (#438 item 6). */
    fun gracePeriodMs(platform: Platform): Long =
        graceConfig.forPlatform(platform).gracePeriodMs

    /** Authoritative-signal destructive grace for [platform] (#431/#438 item 6). */
    fun authoritativeGraceMs(platform: Platform): Long =
        graceConfig.forPlatform(platform).authoritativeGraceMs

    /** Screen-implied resume-from-Paused grace for [platform] (#605/#438 item 6). */
    fun pauseResumeGraceMs(platform: Platform): Long =
        graceConfig.forPlatform(platform).pauseResumeGraceMs

    /**
     * Accept-consumption grace for [platform] (#438 B3 / #762 D2): how long an accepted-pending-
     * consumption offer stays consumable by the task-edge mint. Per-platform (DoorDash 120s, Uber
     * 600s) — see [GraceConfig.acceptGraceMs].
     */
    fun acceptGraceMs(platform: Platform): Long =
        graceConfig.forPlatform(platform).acceptGraceMs

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
            // #762 D2: a coarse in-job surface implies Online exactly as the phased task flows do.
            Flow.TaskActive,
            Flow.PostTask -> Mode.Online

            Flow.SessionEnded -> Mode.Offline
            Flow.Idle -> null // Idle is ambiguous — could be offline or between offers
            // #736: the unassign sheet is online, but the rule always ships an explicit
            // modeHint("online") that wins above (line 82), so this flow-inference arm is dead for
            // it. Treated as Idle-equivalent (ambiguous → null) for exhaustiveness.
            Flow.TaskUnassigned -> null
            null -> null
        }
    }
}
