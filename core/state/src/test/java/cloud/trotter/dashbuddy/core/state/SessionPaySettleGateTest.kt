package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The **settle gate** on the dash running total (#1029).
 *
 * The platform renders that total as an animated digit-wheel and captures land mid-spin.
 * `parseGlyphCurrency` throws out the malformed intermediates at the parse layer, but roughly one
 * fielded read in eight is well-FORMED and wrong ($470.00 during a $16.70 dash) — indistinguishable
 * from a real figure by inspection. What separates them is TIME, and specifically ELAPSED time, not
 * repetition: `IdleFields.dedupeHash` folds `sessionPay` into the screen's observation identity and
 * `FrameGate.admit` drops an identical frame, so a settled wheel is admitted exactly ONCE. A read
 * therefore commits only once it has stood **unchallenged** for `sessionPaySettleMs`, landed by
 * lazy expiry on the next observation past its deadline — usually the `SESSION_PAY_SETTLE` wake
 * timer, which on a settled wheel is the only observation that will ever come.
 */
class SessionPaySettleGateTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()

    /** The settle window under test — read from the SSOT, never a literal. */
    private val settle = GraceConfig.SESSION_PAY_SETTLE_MS

    private val t0 = 10_000L

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun idleScreen(sessionPay: Double?, timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Online,
        parsed = ParsedFields.IdleFields(sessionPay = sessionPay),
    )

    /** The `SESSION_PAY_SETTLE` wake timer, routed at this region (#438 8a). */
    private fun timeout(timestamp: Long) = Observation.Timeout(
        timestamp = timestamp,
        type = TimeoutType.SESSION_PAY_SETTLE,
        targetPlatform = Platform.DoorDash,
    )

    private fun region(
        runningEarnings: Double = 0.0,
        pending: PendingSessionPay? = null,
    ) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session(sessionId = "s1", startedAt = 100L, runningEarnings = runningEarnings),
        lastObservedAt = 500L,
        pendingSessionPay = pending,
    )

    /** One observation through the real steppers. */
    private fun step(region: PlatformRegion, obs: Observation): PlatformRegion {
        val flow = FlowRegion(flow = region.lastActedFlow ?: Flow.Idle)
        val nextFlow = flowStepper.step(flow, obs)
        return stepper.step(region, flow, nextFlow, obs, policy)
    }

    /** Fold `(timestamp, read)` pairs through the real stepper, one frame each. */
    private fun feed(start: PlatformRegion, vararg reads: Pair<Long, Double?>): PlatformRegion {
        var region = start
        for ((t, read) in reads) region = step(region, idleScreen(read, t))
        return region
    }

    private fun assertPark(region: PlatformRegion, value: Double, since: Long, deadline: Long) {
        val pend = region.pendingSessionPay
        assertNotNull("expected a parked read", pend)
        assertEquals(value, pend!!.value, 0.0001)
        assertEquals("park.since", since, pend.since)
        assertEquals("park.deadline", deadline, pend.deadline)
    }

    // =========================================================================
    // Parking
    // =========================================================================

    @Test
    fun `a single read does NOT commit on its own frame`() {
        val result = feed(region(runningEarnings = 16.70), t0 to 470.00)
        assertEquals(
            "one sighting must not move the committed figure",
            16.70, result.session?.runningEarnings,
        )
        assertPark(result, 470.00, t0, t0 + settle)
    }

    @Test
    fun `a mid-spin read is replaced by the next read, and the deadline moves with it`() {
        val result = feed(region(), t0 to 470.00, (t0 + 200L) to 16.70)
        assertEquals("nothing may commit inside the window", 0.0, result.session?.runningEarnings)
        assertPark(result, 16.70, t0 + 200L, t0 + 200L + settle)
    }

    @Test
    fun `a repeated read within the window keeps the ORIGINAL deadline`() {
        // An identity change (some other screen) and a return must not extend the window —
        // otherwise a wheel re-rendering the same figure could hold the commit off forever.
        val result = feed(region(), t0 to 16.70, (t0 + 1_000L) to 16.70)
        assertPark(result, 16.70, t0, t0 + settle)
        assertEquals(0.0, result.session?.runningEarnings)
    }

    @Test
    fun `a read equal to the committed figure clears the park`() {
        val result = feed(region(runningEarnings = 16.70, pending = PendingSessionPay(470.00, t0, t0 + settle)), t0 to 16.70)
        assertEquals(16.70, result.session?.runningEarnings)
        assertNull(
            "a read that equals the committed total means the wheel is at rest — drop the park",
            result.pendingSessionPay,
        )
    }

    @Test
    fun `a null read leaves both the committed figure and the park alone`() {
        val park = PendingSessionPay(25.20, t0, t0 + settle)
        val result = feed(region(runningEarnings = 16.70, pending = park), (t0 + 1L) to null)
        assertEquals(16.70, result.session?.runningEarnings)
        assertEquals(park, result.pendingSessionPay)
    }

    // =========================================================================
    // Committing
    // =========================================================================

    @Test
    fun `the parked read commits by lazy expiry on the next observation past its deadline`() {
        val parked = feed(region(), t0 to 470.00, (t0 + 200L) to 16.70)
        val result = feed(parked, (t0 + 200L + settle + 1L) to null)
        assertEquals(16.70, result.session?.runningEarnings)
        assertNull("the park is spent once it commits", result.pendingSessionPay)
    }

    @Test
    fun `the wake timer commits the park when no further frame arrives`() {
        // THE FrameGate case, and the reason the timer exists: on a settled wheel every later
        // frame carries the identical observation identity (IdleFields.dedupeHash folds in
        // sessionPay), so FrameGate.admit drops it and NO screen observation ever reaches the
        // machine again. Only SESSION_PAY_SETTLE can land the figure.
        val parked = feed(region(), t0 to 470.00, (t0 + 200L) to 16.70)
        val result = step(parked, timeout(t0 + 200L + settle + 1L))
        assertEquals(16.70, result.session?.runningEarnings)
        assertNull(result.pendingSessionPay)
    }

    @Test
    fun `a wake timer BEFORE the deadline does nothing`() {
        val parked = feed(region(), t0 to 16.70)
        val result = step(parked, timeout(t0 + settle - 1L))
        assertEquals(0.0, result.session?.runningEarnings)
        assertPark(result, 16.70, t0, t0 + settle)
    }

    @Test
    fun `a real change commits once unchallenged for the window`() {
        val parked = feed(region(runningEarnings = 16.70), t0 to 25.20)
        assertEquals("still held inside the window", 16.70, parked.session?.runningEarnings)

        val result = step(parked, timeout(t0 + settle + 1L))
        assertEquals(25.20, result.session?.runningEarnings)
        assertNull(result.pendingSessionPay)
    }

    @Test
    fun `a flicker away and back never commits the flicker`() {
        // The wheel spins through 470 between two settled 16.70 reads: the second read clears the
        // park outright, so even a later wake timer has nothing to commit.
        val flicked = feed(region(runningEarnings = 16.70), t0 to 470.00, (t0 + 300L) to 16.70)
        assertNull("the park is dropped, not held", flicked.pendingSessionPay)

        val result = step(flicked, timeout(t0 + settle + 1L))
        assertEquals(16.70, result.session?.runningEarnings)
    }

    @Test
    fun `two DIFFERENT spin values park only the newest, and neither commits early`() {
        val result = feed(
            region(runningEarnings = 16.70),
            t0 to 470.00,
            (t0 + 200L) to 580.00,
            (t0 + 400L) to 70.00,
        )
        assertEquals(16.70, result.session?.runningEarnings)
        assertPark(result, 70.00, t0 + 400L, t0 + 400L + settle)
    }

    @Test
    fun `expiry with no session drops the park without inventing a figure`() {
        val sessionless = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Offline,
            pendingSessionPay = PendingSessionPay(470.00, t0, t0 + settle),
        )
        val result = step(sessionless, timeout(t0 + settle + 1L))
        assertNull("no session may be invented to hold it", result.session)
        assertNull(result.pendingSessionPay)
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Test
    fun `the park is cleared when the session ends`() {
        var flow = FlowRegion(flow = Flow.Idle)
        var region = region(
            runningEarnings = 16.70,
            pending = PendingSessionPay(470.00, 4_000L, 4_000L + settle),
        )

        // The dash summary arms the (graced) session end...
        val summary = Observation.Screen(
            timestamp = 5_000L,
            captureId = null,
            ruleId = "doordash.screen.dash_summary",
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.SessionEnded,
            modeHint = Mode.Offline,
            parsed = ParsedFields.None,
        )
        var nextFlow = flowStepper.step(flow, summary)
        region = stepper.step(region, flow, nextFlow, summary, policy)
        flow = nextFlow

        // ...and a frame well past the grace deadline commits it.
        val later = summary.copy(timestamp = 5_000L + 60L * 60_000L)
        nextFlow = flowStepper.step(flow, later)
        region = stepper.step(region, flow, nextFlow, later, policy)

        assertNull("the session must actually be gone for this to prove anything", region.session)
        assertNull(
            "an un-settled read describes the dash that just ended",
            region.pendingSessionPay,
        )
    }

    @Test
    fun `the park is cleared when a NEW session starts`() {
        // A read parked against the previous dash must never settle against this one.
        val offline = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Offline,
            pendingSessionPay = PendingSessionPay(470.00, 8_000L, 8_000L + settle),
        )
        val obs = idleScreen(sessionPay = null, timestamp = 9_000L)
        val nextFlow = flowStepper.step(FlowRegion(), obs)
        val started = stepper.step(offline, FlowRegion(), nextFlow, obs, policy)

        assertEquals("a session must have been minted", 9_000L, started.session?.startedAt)
        assertNull(started.pendingSessionPay)
    }

    @Test
    fun `a receipt's own session total clears a stale park`() {
        // The PostTask receipt is not wheel-rendered, so it writes runningEarnings immediately
        // (#1029 residual). It is NEWER evidence than any parked idle read, so the park must go —
        // otherwise a later expiry would overwrite the receipt's figure.
        val parked = feed(region(runningEarnings = 16.70), t0 to 470.00)
        val receipt = Observation.Screen(
            timestamp = t0 + 100L,
            captureId = null,
            ruleId = "doordash.screen.delivery_summary_expanded",
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.PostTask,
            modeHint = Mode.Online,
            parsed = ParsedFields.PostTaskFields(totalPay = 8.50, sessionEarnings = 25.20),
        )
        val result = step(parked, receipt)
        assertEquals(25.20, result.session?.runningEarnings)
        assertNull("a stale park must not survive newer evidence", result.pendingSessionPay)
    }
}
