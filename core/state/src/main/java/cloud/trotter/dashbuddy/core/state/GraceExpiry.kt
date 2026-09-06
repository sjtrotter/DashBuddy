package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * When a GRACE deadline counts as lapsed (#1054) — the ONE predicate both of
 * [PlatformRegionStepper]'s grace expiries use.
 *
 * Lives here rather than on the stepper because that file is already past the #237 ceiling
 * (~1 270 lines) and this is a self-contained rule with a long justification: adding both to the
 * stepper would have grown it further, which round 3 of the review called out.
 *
 * ## The rule
 *
 * Past the deadline is always a lapse. Landing exactly ON it is a lapse **only for this timer's own
 * fire** — same [TimeoutType], same [Platform] — and each half of that is load-bearing.
 *
 * **A timer must count at equality.** `ModeEffects.diffDeadlineTimer` arms for exactly
 * `deadline - obs.timestamp`, so the ordinary landing IS the deadline. Under a strict `>` that fire
 * was a no-op with nothing left to wake the pending, and the ordinary frame the old code assumed
 * would re-drive the lazy expiry does not exist for the case `GRACE_COMMIT` was built for (#431 —
 * offline with the app backgrounded; an unchanged screen is FrameGate-deduplicated).
 *
 * **An ordinary FRAME must not** (round 2). Both graces have a CANCEL arm that competes with their
 * own commit: a paused frame cancels a graced resume (#605), a task-flow frame cancels a
 * misrecognized `SESSION_END` (#431). The expiry runs at the top of `stepCore`, before the frame's
 * own transition, so a plain `>=` let a frame stamped exactly on the deadline commit the very thing
 * it arrived to contradict — and the resume case did worse than that, minting a session
 * (`applyModeTransition` mints when `session == null`) that the following Online→Paused transition
 * then left with no `DASH_START` describing it. A frame stamped strictly LATER than the deadline
 * commits exactly as it always did; only the tie changes, and it changes toward the arm that has
 * evidence on screen.
 *
 * **And a DIFFERENT timer must not either** (round 3). Granting equality to any
 * `Observation.Timeout` looked harmless until you notice the deadlines coincide *systematically*:
 * `GraceConfig.PAUSE_RESUME_GRACE_MS` and `RECEIPT_EXPAND_GRACE_MS` are both 8 000 ms, and every
 * timer in a region shares one clock. A `SESSION_PAUSED_SAFETY` fire stamped on a resume deadline
 * would commit Paused→Online here, at the top of the step — and then `handleTimeout`'s
 * `prev.mode == Paused` guard is false, so the safety net's own Paused→Offline + `SESSION_END`
 * grace is silently dropped and the dash stays Online forever. The (type, platform) test is the
 * same shape `diffDeadlineTimer`'s early-wake guard already uses, which is the point: a timer's
 * fire is evidence about the pending it was armed for and about nothing else.
 *
 * ## Who does NOT use this
 *
 * `PlatformRegion.pendingSessionPay` keeps a plain `>=`. It has no cancel arm to lose: rule (f) of
 * the settle gate already makes a contradicting read on the expiring frame SUPERSEDE the park, so
 * the frame's own evidence wins at equality by construction.
 *
 * Pure and platform-agnostic — the platform is passed in from the region, never a literal
 * (Principle 8) — and driven by `obs.timestamp`, never a wall clock, so a replay reproduces
 * exactly (Principle 1).
 */
internal fun deadlineLapsed(
    deadline: Long,
    obs: Observation,
    type: TimeoutType,
    platform: Platform,
): Boolean = obs.timestamp > deadline ||
    (
        obs.timestamp == deadline &&
            obs is Observation.Timeout && obs.type == type && obs.platform == platform
        )
