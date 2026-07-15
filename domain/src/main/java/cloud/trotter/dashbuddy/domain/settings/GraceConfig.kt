package cloud.trotter.dashbuddy.domain.settings

import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * Per-platform grace / timing configuration (#438 item 6, Principle 8 — the
 * per-platform timing seam). One grace-constant set used to time every
 * platform; the values are genuinely platform-specific (e.g. [expandSettleMs]
 * waits on DoorDash's summary-dialog animation, not a universal truth), so they
 * are keyed by [Platform] instead of hard-coded globals.
 *
 * The five fields are seeded with today's compile-time constants (formerly
 * `TransitionPolicy.DEFAULT_GRACE_MS` / `AUTHORITATIVE_GRACE_MS` /
 * `PAUSE_RESUME_GRACE_MS` and `EffectMap.PAUSE_TIMEOUT_BUFFER_MS` /
 * `EXPAND_SETTLE_MS`). This companion is now the SSOT for those defaults;
 * `TransitionPolicy` / `EffectMap` re-export them only for test/comment
 * back-compat.
 *
 * A platform absent from the override map resolves to [DEFAULT], so with no
 * saved overrides every platform behaves exactly as the old constants did.
 */
data class GraceConfig(
    /** Provisional-destructive commit grace (session end / task retire on idle). */
    val gracePeriodMs: Long = DEFAULT_GRACE_MS,
    /** Short grace for an authoritative-looking destructive signal (#431). */
    val authoritativeGraceMs: Long = AUTHORITATIVE_GRACE_MS,
    /** Grace for a screen-implied resume out of Paused (#605). */
    val pauseResumeGraceMs: Long = PAUSE_RESUME_GRACE_MS,
    /** Safety buffer added to a reported pause countdown before the offline timeout. */
    val pauseTimeoutBufferMs: Long = PAUSE_TIMEOUT_BUFFER_MS,
    /** Settle delay before the EXPAND_EARNINGS tap (waits on the dialog animation). */
    val expandSettleMs: Long = EXPAND_SETTLE_MS,
    /**
     * How long an accepted-pending-consumption offer stays consumable by the task-edge mint
     * (#438 B3, formerly `PlatformRegionStepper.ACCEPT_GRACE_MS`; #762 D2 made it per-platform).
     * An accept is normally followed by the task flow within a couple of minutes; a generous window
     * recovers the F3 teardown race while a stale survivor still can't leak into an unrelated later
     * job. This is a per-platform grace value (Principle 8): on a platform with a fine-grained task
     * flow (DoorDash) the survivor is consumed within seconds by the first pickup frame, so the
     * default is 120s; on a coarse platform (Uber, whose `on_job_view`/`task:active` frame consumes
     * the accept within seconds too) it is widened to a realistic full-drive fallback for a MISSED
     * consume frame — see [Companion.codeDefault].
     */
    val acceptGraceMs: Long = DEFAULT_ACCEPT_GRACE_MS,
) {
    companion object {
        const val DEFAULT_GRACE_MS = 10_000L
        const val AUTHORITATIVE_GRACE_MS = 2_500L
        const val PAUSE_RESUME_GRACE_MS = 8_000L
        const val PAUSE_TIMEOUT_BUFFER_MS = 1_000L
        const val EXPAND_SETTLE_MS = 500L

        /**
         * Default accept-consumption grace (DoorDash and any platform without an override): a fine-
         * grained task flow consumes the accepted survivor within a couple of minutes. SSOT for the
         * former `PlatformRegionStepper.ACCEPT_GRACE_MS`.
         */
        const val DEFAULT_ACCEPT_GRACE_MS = 120_000L

        /**
         * Uber's accept-consumption grace (#762 D2): 10 min. On Uber no fine-grained task flow
         * exists between accept and the store — the coarse `task:active` (`on_job_view`) surface
         * usually consumes the accept within seconds via its first frame, so this wide window is a
         * belt-and-suspenders fallback for a MISSED consume frame that must still span a realistic
         * drive rather than expire mid-trip.
         */
        const val UBER_ACCEPT_GRACE_MS = 600_000L

        /** Code-constant default applied to any platform without an override. */
        val DEFAULT = GraceConfig()

        /**
         * Per-platform **code defaults** (before any user override), keyed by [Platform] (Principle
         * 8 — data keyed by platform, never `== Platform.X` in logic). A platform absent here uses
         * [DEFAULT]; the only current divergence is Uber's wider [acceptGraceMs]. A future
         * per-platform grace editor's DataStore override still wins over this in
         * [GraceConfigProvider.forPlatform].
         */
        private val CODE_DEFAULTS: Map<Platform, GraceConfig> = mapOf(
            Platform.Uber to GraceConfig(acceptGraceMs = UBER_ACCEPT_GRACE_MS),
        )

        /** The code default for [platform] — its [CODE_DEFAULTS] entry, else [DEFAULT]. */
        fun codeDefault(platform: Platform): GraceConfig = CODE_DEFAULTS[platform] ?: DEFAULT
    }
}

/**
 * Synchronous value provider for the per-platform [GraceConfig] snapshot (#438
 * item 6, vet M7). The Hilt-bound implementation reads
 * [PlatformPreferences.graceConfig]`.value` — the same eagerly-materialized
 * `.value` snapshot pattern the engine already uses for `evidenceConfig` — so
 * the pure steppers and `EffectMap` never collect a Flow inside a reducer. Each
 * use site reads one atomic snapshot **synchronously at the moment it computes a
 * deadline/duration**, and every value is immediately stored in state or a timer
 * — so a config flip landing mid-step is observationally identical to the edit
 * landing between two steps (the five fields carry no cross-field invariant).
 *
 * REPLAY-DETERMINISM TRADEOFF (pre-accepted, #438 design doc §B6): a grace edited
 * between a live run and its crash replay changes the replayed commit timing — a
 * DataStore-backed value is not replay-stable the way the old compile-time
 * constants were. Accepted for the single-user alpha (grace edits are rare dev
 * actions and the journal replays observations, not effect timing); documented
 * here and at every injection site so the adversarial review doesn't re-litigate
 * it.
 */
fun interface GraceConfigProvider {

    /** The current per-platform override snapshot — one atomic read per use site. */
    fun snapshot(): Map<Platform, GraceConfig>

    /** The config for [platform]: a user override if present, else its per-platform code default. */
    fun forPlatform(platform: Platform): GraceConfig =
        snapshot()[platform] ?: GraceConfig.codeDefault(platform)

    companion object {
        /** Code-constant defaults — the test / fallback provider (no overrides). */
        val Defaults = GraceConfigProvider { emptyMap() }
    }
}
