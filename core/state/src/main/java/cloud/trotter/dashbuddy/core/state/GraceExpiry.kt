package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType

/**
 * When a deadline-bearing pending has lapsed (#1054) — the rules
 * [PlatformRegionStepper]'s lazy expiries share.
 *
 * Housed outside the stepper because that file is already past the #237 ceiling and this is a
 * self-contained rule with a long justification.
 *
 * ## Identity, not arithmetic (round 4)
 *
 * A timer's fire is recognised by the deadline it CARRIES, not by where its wall-clock stamp
 * happens to fall. Every earlier round of this issue patched the same substitution: the strict `>`
 * that ignored a fire landing exactly on its own deadline; the early-wake re-arm that papered over
 * an NTP step-back; the equality carve-out that let ANY timeout through at the tie; its narrowing
 * to `(type, platform)`, which was still a timestamp-COINCIDENCE test standing in for "is this the
 * wake armed for THIS pending". [ObservationPayload.GraceWake] answers the real question, and the
 * codebase already worked this way for offers — `OfferExpiry(offerHash)` resolves BY hash.
 *
 * Two properties fall out, and both were bugs before:
 *
 * - **A fire is authoritative whenever it arrives.** Armed for `deadline - obs.timestamp` and
 *   stamped with `System.currentTimeMillis()`, it lands ON the deadline ordinarily and BEFORE it
 *   after a clock step-back. The window still elapsed; only the stamp moved. So there is no
 *   re-arm branch left in `ModeEffects.diffDeadlineTimer` — nothing gets lost, so nothing needs
 *   rescuing.
 * - **A fire from a REPLACED pending is inert.** It carries the OLD deadline, so it matches
 *   nothing. That is what stops a superseded settle timer committing a mid-spin figure, and a
 *   stale `SESSION_PAUSED_SAFETY` fire from a previous pause ending the current one.
 *
 * ## What a FRAME does is unchanged
 *
 * An ordinary observation still lapses a grace only strictly PAST the deadline, so a contradicting
 * frame stamped exactly on it reaches its own cancel arm instead — a paused frame cancels a graced
 * resume (#605), a task-flow frame cancels a misrecognized `SESSION_END` (#431). The expiry runs at
 * the top of `stepCore`, ahead of the frame's own transition, so the tie has to go to the arm with
 * evidence on screen. The settle park keeps `>=` for frames, which is #1029 rule (e)'s own design
 * choice and safe there because rule (f) makes a contradicting read on the expiring frame supersede
 * the park anyway.
 *
 * Pure, and driven by `obs.timestamp` — never a wall clock — so a replay reproduces exactly
 * (Principle 1). Platform-agnostic by construction: there is no platform in the question at all any
 * more, because identity does the work the `(type, platform)` coincidence test was doing.
 */

/**
 * Is [this] the wake that was armed for a pending of [type] whose deadline is [deadline]?
 *
 * False for a frame, for another timer type, for a fire carrying a DIFFERENT deadline (a replaced
 * pending), and for a payload-less fire — the last being an old-shape timeout from a journal row
 * written before round 4, or a hand-built timeout in a test. Fail-closed: an unrecognised fire
 * simply does not lapse anything, and the frame path still commits strictly past the deadline.
 */
internal fun Observation.isWakeFor(type: TimeoutType, deadline: Long?): Boolean =
    deadline != null &&
        this is Observation.Timeout &&
        this.type == type &&
        (payload as? ObservationPayload.GraceWake)?.deadline == deadline

/**
 * Has a GRACE deadline lapsed as of [obs]? — used by both `pendingDestructive` and
 * `pendingModeResume`.
 *
 * Strictly past for an ordinary observation, or this pending's own wake whenever it arrives. See
 * the file KDoc for why those are the two halves.
 */
internal fun graceLapsed(deadline: Long, obs: Observation, type: TimeoutType): Boolean =
    obs.timestamp > deadline || obs.isWakeFor(type, deadline)
