package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.mintWakeId

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
 * **Round 5 makes that identity a GENERATION, not the deadline.** Two successive pendings can
 * legitimately hold the same deadline: after a clock step-back a replacement park computes the
 * identical `now + settleWindow`, so the superseded wake matched and committed the replacement after
 * essentially no time in its own window — a mid-spin figure, which is exactly what #1029's settle
 * gate exists to reject. A `wakeId` drawn from `PlatformRegion.wakeSeq` cannot collide.
 *
 * Two properties fall out, and both were bugs before:
 *
 * - **A fire is authoritative whenever it arrives.** Armed for `deadline - obs.timestamp` and
 *   stamped with `System.currentTimeMillis()`, it lands ON the deadline ordinarily and BEFORE it
 *   after a clock step-back. The window still elapsed; only the stamp moved. So there is no
 *   re-arm branch left in `ModeEffects.diffDeadlineTimer` — nothing gets lost, so nothing needs
 *   rescuing.
 * - **A fire from a REPLACED pending is inert.** It carries the OLD generation, so it matches
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
 * Is [this] the wake that was armed for a pending of [type] whose generation is [wakeId]?
 *
 * False for a frame, for another timer type, for a fire carrying a DIFFERENT id (a replaced
 * pending), and for a payload-less fire — the last being an old-shape timeout from a journal row
 * written before round 4, or a hand-built timeout in a test. **Id `0` never matches**, on either
 * side: it is the reserved "legacy, unidentified" value a pre-round-5 snapshot decodes to, and such
 * a pending is lapsed by its timestamp alone, exactly as it was under the code that wrote it.
 * Fail-closed throughout — an unrecognised fire lapses nothing, and the frame path still commits
 * strictly past the deadline.
 */
internal fun Observation.isWakeFor(type: TimeoutType, wakeId: Long?): Boolean =
    wakeId != null && wakeId != 0L &&
        this is Observation.Timeout &&
        this.type == type &&
        (payload as? ObservationPayload.GraceWake)?.wakeId == wakeId

/**
 * Has a GRACE deadline lapsed as of [obs]? — used by both `pendingDestructive` and
 * `pendingModeResume`.
 *
 * Strictly past for an ordinary observation, or this pending's own wake whenever it arrives. See
 * the file KDoc for why those are the two halves.
 */
internal fun graceLapsed(deadline: Long, wakeId: Long, obs: Observation, type: TimeoutType): Boolean =
    obs.timestamp > deadline || obs.isWakeFor(type, wakeId)

/**
 * Install [next] as this region's destructive pending, minting a fresh [PendingDestructive.wakeId]
 * only when the deadline actually MOVED (#1054 round 5).
 *
 * The two tighten sites — the dash summary's `minOf` against a standing offline grace, and #1033's
 * collapsed-vs-expanded receipt `minOf` — re-derive the pending on every qualifying frame, and most
 * of those frames change nothing. A moved deadline is a new pending as far as its timer is
 * concerned, because the standing arm would fire at the old, LATER instant and by then the window
 * it was meant to protect has been over for a while. An unchanged deadline must keep its id, or a
 * re-render would cancel and re-arm the timer on every frame.
 *
 * [prev] is the pending as it stood (null for a fresh arm, which always mints).
 */
internal fun PlatformRegion.withWakeIdIfDeadlineMoved(
    prev: PendingDestructive?,
    next: PendingDestructive,
): PlatformRegion {
    if (prev != null && prev.deadline == next.deadline) {
        return copy(pendingDestructive = next.copy(wakeId = prev.wakeId))
    }
    val (withId, wakeId) = mintWakeId()
    return withId.copy(pendingDestructive = next.copy(wakeId = wakeId))
}

/**
 * Does this `SESSION_PAUSED_SAFETY` fire authoritatively describe [region]'s current pause
 * (#1054 rounds 4–5)?
 *
 * Two arms, and the second is an UPGRADE concession rather than a design choice:
 *
 * 1. **Identity.** The ordinary case: the fire carries the generation of the pause currently armed.
 *    A stale fire from a PREVIOUS pause carries the earlier one and must not end this one — which
 *    matters because the deadline is state now, so a re-pause arms a new net while an old coroutine
 *    may still be in flight.
 * 2. **Legacy fail-OPEN.** A payload-less fire is accepted when the region has NO pause safety
 *    armed at all. That is a pre-round-4 arm: its deadline lived only in the engine's timer map, so
 *    there is no identity to check and nothing it could be confused with. Refusing it was a real
 *    upgrade regression — a master-era snapshot's journal tail holds exactly such a row, and
 *    ignoring it left the region Paused with the session still live, so the `GRACE_COMMIT` row
 *    behind it found no destructive pending to commit and a genuinely-ended dash was checkpointed
 *    as running. Once the region HAS a `pauseSafety`, an unidentified fire is refused: it is
 *    strictly older than the arm in state.
 *
 * The caller still requires [Mode.Paused]; this answers only "is this fire about the pause we are
 * in".
 */
internal fun safetyFireIsAuthoritative(region: PlatformRegion, obs: Observation.Timeout): Boolean {
    val armed = region.pauseSafety
    if (armed != null) return obs.isWakeFor(TimeoutType.SESSION_PAUSED_SAFETY, armed.wakeId)
    return (obs.payload as? ObservationPayload.GraceWake) == null
}
