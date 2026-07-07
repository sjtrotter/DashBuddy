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
) {
    companion object {
        const val DEFAULT_GRACE_MS = 10_000L
        const val AUTHORITATIVE_GRACE_MS = 2_500L
        const val PAUSE_RESUME_GRACE_MS = 8_000L
        const val PAUSE_TIMEOUT_BUFFER_MS = 1_000L
        const val EXPAND_SETTLE_MS = 500L

        /** Code-constant default applied to any platform without an override. */
        val DEFAULT = GraceConfig()
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

    /** The config for [platform], falling back to [GraceConfig.DEFAULT]. */
    fun forPlatform(platform: Platform): GraceConfig =
        snapshot()[platform] ?: GraceConfig.DEFAULT

    companion object {
        /** Code-constant defaults — the test / fallback provider (no overrides). */
        val Defaults = GraceConfigProvider { emptyMap() }
    }
}
