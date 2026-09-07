package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GraceExpiry` — timer IDENTITY, not timestamp arithmetic (#1054 round 4).
 *
 * The predicate under test replaced four successive patches on the same substitution: the strict
 * `>` that ignored a fire landing exactly on its own deadline, the early-wake re-arm that papered
 * over a clock step-back, round 2's equality carve-out for any timeout, and round 3's narrowing of
 * that to `(type, platform)`. All of them asked "did the stamps coincide"; [isWakeFor] asks "is this
 * the wake armed for THIS pending", which is the question the codebase already answers for offers
 * (`OfferExpiry(offerHash)` resolves BY hash).
 *
 * Round 5 makes the identity a per-region GENERATION rather than the deadline, because a deadline
 * is not unique either: a replacement pending can carry the deadline of the one it replaced (after
 * a clock step-back a new park computes the identical `now + settleWindow`), and the superseded
 * wake then committed it after essentially no time in its own window.
 */
class GraceExpiryTest {

    private val deadline = 20_000L
    private val wakeId = 7L

    private fun wake(
        type: TimeoutType = TimeoutType.GRACE_COMMIT,
        armedFor: Long? = wakeId,
        at: Long = deadline,
        platform: Platform = Platform.DoorDash,
    ) = Observation.Timeout(
        timestamp = at,
        type = type,
        targetPlatform = platform,
        payload = armedFor?.let { ObservationPayload.GraceWake(it) },
    )

    private fun frame(at: Long) = Observation.Screen(
        timestamp = at,
        captureId = null,
        ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Online,
        parsed = ParsedFields.IdleFields(),
    )

    // ---- isWakeFor ----

    @Test
    fun `a matching type and generation is this pending's wake, whatever the timestamp says`() {
        // The whole point. The timer is armed for `deadline - obs.timestamp` and fired stamped with
        // the wall clock, so it lands ON the deadline ordinarily and BEFORE it after an NTP
        // step-back. The window still elapsed; only the stamp moved.
        for (at in listOf(deadline - 5_000L, deadline - 1L, deadline, deadline + 1L, deadline + 60_000L)) {
            assertTrue(
                "a wake stamped at $at is still the wake armed for generation $wakeId",
                wake(at = at).isWakeFor(TimeoutType.GRACE_COMMIT, wakeId),
            )
        }
    }

    @Test
    fun `a DIFFERENT generation is a REPLACED pending's wake, and matches nothing`() {
        // The stale-fire case, and the reason this is not "did the stamps coincide": a pending that
        // was re-armed later leaves an old coroutine in flight, and committing on its fire would
        // commit the pending that SUPERSEDED it — a mid-spin figure for the settle gate.
        assertFalse(wake(armedFor = wakeId - 1).isWakeFor(TimeoutType.GRACE_COMMIT, wakeId))
        assertFalse(wake(armedFor = wakeId + 1).isWakeFor(TimeoutType.GRACE_COMMIT, wakeId))
    }

    @Test
    fun `the SAME deadline with a different generation matches nothing - round 5's whole point`() {
        // Astra's case. A replacement park computed after a clock step-back gets the identical
        // `now + settleWindow`, so under round 4's deadline-as-identity the superseded wake matched
        // and committed the $30 read after essentially zero time in its own window. The deadline is
        // not in the question at all now.
        assertFalse(
            "generation 1's wake is not generation 2's, however the deadlines line up",
            wake(armedFor = 1L).isWakeFor(TimeoutType.GRACE_COMMIT, 2L),
        )
    }

    @Test
    fun `another timer type's fire is never this pending's wake`() {
        // Coincident deadlines are SYSTEMATIC: `PAUSE_RESUME_GRACE_MS` and `RECEIPT_EXPAND_GRACE_MS`
        // are both 8 000 ms and a region's timers share one clock.
        assertFalse(
            wake(type = TimeoutType.SESSION_PAUSED_SAFETY).isWakeFor(TimeoutType.GRACE_COMMIT, wakeId),
        )
        assertFalse(
            wake(type = TimeoutType.MODE_RESUME_COMMIT).isWakeFor(TimeoutType.GRACE_COMMIT, wakeId),
        )
    }

    @Test
    fun `a FRAME is never a wake, however it is stamped`() {
        assertFalse(frame(deadline).isWakeFor(TimeoutType.GRACE_COMMIT, wakeId))
        assertFalse(frame(deadline + 10_000L).isWakeFor(TimeoutType.GRACE_COMMIT, wakeId))
    }

    @Test
    fun `a payload-less fire matches nothing - fail-closed on an old-shape timeout`() {
        // A journal row written before round 4, or a hand-built timeout. It carries no identity, so
        // it lapses nothing and the pending waits for its own wake; the frame path still commits it
        // strictly past the deadline, so nothing is stranded.
        assertFalse(wake(armedFor = null).isWakeFor(TimeoutType.GRACE_COMMIT, wakeId))
    }

    @Test
    fun `a null generation can never be matched`() {
        // The pause-safety net is nullable — no pause, no pending. A fire must not match
        // "nothing armed".
        assertFalse(wake().isWakeFor(TimeoutType.GRACE_COMMIT, null))
        assertFalse(wake(armedFor = null).isWakeFor(TimeoutType.GRACE_COMMIT, null))
    }

    @Test
    fun `generation ZERO never matches - a legacy pending is lapsed by its timestamp alone`() {
        // 0 is the reserved default a pre-round-5 snapshot decodes to. Matching it would let ANY
        // legacy-shaped fire lapse ANY legacy pending; refusing it leaves exactly the pre-#1054
        // behaviour those pendings were written under.
        assertFalse(wake(armedFor = 0L).isWakeFor(TimeoutType.GRACE_COMMIT, 0L))
        assertFalse(wake(armedFor = 0L).isWakeFor(TimeoutType.GRACE_COMMIT, wakeId))
        assertFalse(wake().isWakeFor(TimeoutType.GRACE_COMMIT, 0L))
        assertTrue(
            "and it still lapses once the deadline has genuinely passed",
            graceLapsed(deadline, 0L, frame(deadline + 1L), TimeoutType.GRACE_COMMIT),
        )
    }

    // ---- graceLapsed ----

    @Test
    fun `a frame lapses a grace only STRICTLY past the deadline`() {
        // Equality belongs to the pending's own wake, not to a frame: the expiry runs at the top of
        // `stepCore`, ahead of the frame's own transition, so a contradicting frame stamped exactly
        // on the deadline has to reach its cancel arm instead of committing what it came to
        // contradict (a paused frame cancels a resume #605; a task frame cancels a misrecognized
        // `SESSION_END` #431).
        assertFalse(graceLapsed(deadline, wakeId, frame(deadline - 1L), TimeoutType.GRACE_COMMIT))
        assertFalse(graceLapsed(deadline, wakeId, frame(deadline), TimeoutType.GRACE_COMMIT))
        assertTrue(graceLapsed(deadline, wakeId, frame(deadline + 1L), TimeoutType.GRACE_COMMIT))
    }

    @Test
    fun `this pending's own wake lapses it whenever it arrives`() {
        assertTrue(graceLapsed(deadline, wakeId, wake(at = deadline - 5_000L), TimeoutType.GRACE_COMMIT))
        assertTrue(graceLapsed(deadline, wakeId, wake(at = deadline), TimeoutType.GRACE_COMMIT))
    }

    @Test
    fun `a stale wake before the deadline lapses nothing`() {
        assertFalse(
            graceLapsed(
                deadline, wakeId,
                wake(armedFor = wakeId - 1, at = deadline - 1L), TimeoutType.GRACE_COMMIT,
            ),
        )
    }

    @Test
    fun `a stale wake PAST the deadline still lapses - as an ordinary observation would`() {
        // Not an identity match, but by then any observation lapses the grace: the deadline really
        // has passed. Stated so the asymmetry is deliberate rather than assumed.
        assertTrue(
            graceLapsed(
                deadline, wakeId,
                wake(armedFor = wakeId - 1, at = deadline + 1L), TimeoutType.GRACE_COMMIT,
            ),
        )
    }
}
