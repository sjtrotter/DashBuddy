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
 */
class GraceExpiryTest {

    private val deadline = 20_000L

    private fun wake(
        type: TimeoutType = TimeoutType.GRACE_COMMIT,
        armedFor: Long? = deadline,
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
    fun `a matching type and deadline is this pending's wake, whatever the timestamp says`() {
        // The whole point. The timer is armed for `deadline - obs.timestamp` and fired stamped with
        // the wall clock, so it lands ON the deadline ordinarily and BEFORE it after an NTP
        // step-back. The window still elapsed; only the stamp moved.
        for (at in listOf(deadline - 5_000L, deadline - 1L, deadline, deadline + 1L, deadline + 60_000L)) {
            assertTrue(
                "a wake stamped at $at is still the wake armed for $deadline",
                wake(at = at).isWakeFor(TimeoutType.GRACE_COMMIT, deadline),
            )
        }
    }

    @Test
    fun `a DIFFERENT deadline is a REPLACED pending's wake, and matches nothing`() {
        // The stale-fire case, and the reason this is not "did the stamps coincide": a pending that
        // was re-armed later leaves an old coroutine in flight, and committing on its fire would
        // commit the pending that SUPERSEDED it — a mid-spin figure for the settle gate.
        assertFalse(wake(armedFor = 19_000L).isWakeFor(TimeoutType.GRACE_COMMIT, deadline))
        assertFalse(wake(armedFor = 21_000L).isWakeFor(TimeoutType.GRACE_COMMIT, deadline))
    }

    @Test
    fun `another timer type's fire is never this pending's wake`() {
        // Coincident deadlines are SYSTEMATIC: `PAUSE_RESUME_GRACE_MS` and `RECEIPT_EXPAND_GRACE_MS`
        // are both 8 000 ms and a region's timers share one clock.
        assertFalse(
            wake(type = TimeoutType.SESSION_PAUSED_SAFETY).isWakeFor(TimeoutType.GRACE_COMMIT, deadline),
        )
        assertFalse(
            wake(type = TimeoutType.MODE_RESUME_COMMIT).isWakeFor(TimeoutType.GRACE_COMMIT, deadline),
        )
    }

    @Test
    fun `a FRAME is never a wake, however it is stamped`() {
        assertFalse(frame(deadline).isWakeFor(TimeoutType.GRACE_COMMIT, deadline))
        assertFalse(frame(deadline + 10_000L).isWakeFor(TimeoutType.GRACE_COMMIT, deadline))
    }

    @Test
    fun `a payload-less fire matches nothing - fail-closed on an old-shape timeout`() {
        // A journal row written before round 4, or a hand-built timeout. It carries no identity, so
        // it lapses nothing and the pending waits for its own wake; the frame path still commits it
        // strictly past the deadline, so nothing is stranded.
        assertFalse(wake(armedFor = null).isWakeFor(TimeoutType.GRACE_COMMIT, deadline))
    }

    @Test
    fun `a null deadline can never be matched`() {
        // The pause-safety net's deadline is nullable — no pause, no pending. A fire must not
        // match "nothing armed".
        assertFalse(wake().isWakeFor(TimeoutType.GRACE_COMMIT, null))
        assertFalse(wake(armedFor = null).isWakeFor(TimeoutType.GRACE_COMMIT, null))
    }

    // ---- graceLapsed ----

    @Test
    fun `a frame lapses a grace only STRICTLY past the deadline`() {
        // Equality belongs to the pending's own wake, not to a frame: the expiry runs at the top of
        // `stepCore`, ahead of the frame's own transition, so a contradicting frame stamped exactly
        // on the deadline has to reach its cancel arm instead of committing what it came to
        // contradict (a paused frame cancels a resume #605; a task frame cancels a misrecognized
        // `SESSION_END` #431).
        assertFalse(graceLapsed(deadline, frame(deadline - 1L), TimeoutType.GRACE_COMMIT))
        assertFalse(graceLapsed(deadline, frame(deadline), TimeoutType.GRACE_COMMIT))
        assertTrue(graceLapsed(deadline, frame(deadline + 1L), TimeoutType.GRACE_COMMIT))
    }

    @Test
    fun `this pending's own wake lapses it whenever it arrives`() {
        assertTrue(graceLapsed(deadline, wake(at = deadline - 5_000L), TimeoutType.GRACE_COMMIT))
        assertTrue(graceLapsed(deadline, wake(at = deadline), TimeoutType.GRACE_COMMIT))
    }

    @Test
    fun `a stale wake before the deadline lapses nothing`() {
        assertFalse(
            graceLapsed(deadline, wake(armedFor = 15_000L, at = deadline - 1L), TimeoutType.GRACE_COMMIT),
        )
    }

    @Test
    fun `a stale wake PAST the deadline still lapses - as an ordinary observation would`() {
        // Not an identity match, but by then any observation lapses the grace: the deadline really
        // has passed. Stated so the asymmetry is deliberate rather than assumed.
        assertTrue(
            graceLapsed(deadline, wake(armedFor = 15_000L, at = deadline + 1L), TimeoutType.GRACE_COMMIT),
        )
    }
}
