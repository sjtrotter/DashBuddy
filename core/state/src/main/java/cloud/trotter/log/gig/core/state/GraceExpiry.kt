package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.WakeId

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
 * written before round 4, or a hand-built timeout in a test. **Id `0` never matches**: pending identities are positive [WakeId] values,
 * while old journal payloads remain plain numbers.
 * Fail-closed throughout — an unrecognised fire lapses nothing, and the frame path still commits
 * strictly past the deadline.
 */
internal fun Observation.isWakeFor(type: TimeoutType, wakeId: WakeId?): Boolean =
    wakeId != null &&
        this is Observation.Timeout &&
        this.type == type &&
        (payload as? ObservationPayload.GraceWake)?.wakeId == wakeId.value

/**
 * Has a GRACE deadline lapsed as of [obs]? — used by both `pendingDestructive` and
 * `pendingModeResume` (Paused resumes and post-end Offline starts, subject to frame contradiction).
 *
 * Strictly past for an ordinary observation, or this pending's own wake whenever it arrives. See
 * the file KDoc for why those are the two halves.
 */
internal fun graceLapsed(deadline: Long, wakeId: WakeId, obs: Observation, type: TimeoutType): Boolean =
    obs.timestamp > deadline || obs.isWakeFor(type, wakeId)


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
 * 2. **Legacy fail-OPEN.** A payload-less fire is by construction a pre-round-5 arm — its deadline
 *    lived only in the engine's timer map, so there is no identity to check. It is accepted when
 *    the region has NO pause safety armed, OR when it lands **at or after** the armed one's
 *    deadline. Refusing such a fire was a real upgrade regression, and round 5's `== null` test was
 *    only half of it: a master-era journal tail can contain the PAUSE FRAME too, and replaying that
 *    frame reconstructs a `pauseSafety` — after which round 5 refused the very legacy fire that
 *    frame's own countdown produced, leaving the region Paused with the session still live, so the
 *    `GRACE_COMMIT` row behind it found no destructive pending and a genuinely-ended dash was
 *    checkpointed as running. A non-null net does not prove an unidentified fire predates it: the
 *    replay may have just reconstructed that net from the same legacy history. Landing at or after
 *    the reconstructed deadline is what identifies it as that net's own fire. An unidentified fire
 *    landing strictly BEFORE the armed deadline is still refused — that one really is older than
 *    the arm in state.
 *
 * **Two stated residuals** (round 7), both about the second arm:
 *
 * - **A legacy fire stamped BEFORE its own reconstructed deadline is refused, and that can lose a
 *   terminal transition.** A master-era safety coroutine armed for 20 s whose wall clock rolled back
 *   5 s between arm and fire lands at 25 000 against a reconstructed deadline of 30 000. Master
 *   accepted it, armed the end grace and ended the dash; the replay refuses it, so the
 *   `GRACE_COMMIT` behind it finds no destructive pending and the session is checkpointed as still
 *   running. There is no fix available: identity cannot be recovered across a wall-clock
 *   discontinuity, and a timestamp comparison is the only signal such a fire carries. The exposure
 *   is bounded to ONE process lifetime — the journal tail is 48 h, and every arm from the first run
 *   of this build onward carries an id — so it is accepted rather than papered over with a looser
 *   rule that would let genuinely stale fires end live pauses.
 * - **"Payload-less means pre-round-5" is not strictly true.** `SideEffectEngine`'s rule-driven
 *   timer API accepts any [TimeoutType] and supplies `payload = null`, so a LIVE payload-less safety
 *   fire is constructible today. No checked-in rule invokes it for safety, and the compiler cannot
 *   express that; it is tracked as a provenance residual on #1076.
 *
 * The caller still requires [Mode.Paused]; this answers only "is this fire about the pause we are
 * in".
 */
internal fun safetyFireIsAuthoritative(region: PlatformRegion, obs: Observation.Timeout): Boolean {
    val armed = region.pauseSafety
    if (obs.isWakeFor(TimeoutType.SESSION_PAUSED_SAFETY, armed?.wakeId)) return true
    if ((obs.payload as? ObservationPayload.GraceWake) != null) return false
    return armed == null || obs.timestamp >= armed.deadline
}
