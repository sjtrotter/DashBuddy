package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The **settle gate** on the dash running total (#1029).
 *
 * The platform renders that total as an animated digit-wheel and captures land mid-spin.
 * `parseGlyphCurrency` throws out the malformed intermediates at the parse layer, but roughly one
 * fielded read in eight is well-FORMED and wrong ($470.00 during a $16.70 dash) — indistinguishable
 * from a real figure by inspection. What separates them is TIME: a spin value is transient, a
 * settled value repeats. So `runningEarnings` moves only when two consecutive reads agree.
 */
class SessionPaySettleGateTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()

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

    private fun region(runningEarnings: Double = 0.0, pending: Double? = null) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session(sessionId = "s1", startedAt = 100L, runningEarnings = runningEarnings),
        lastObservedAt = 500L,
        pendingSessionPayQuote = pending,
    )

    /** Fold a sequence of parsed reads through the real stepper, one frame each. */
    private fun feed(start: PlatformRegion, vararg reads: Double?): PlatformRegion {
        var region = start
        var flow = FlowRegion()
        var t = 1000L
        for (read in reads) {
            val obs = idleScreen(read, t)
            val nextFlow = flowStepper.step(flow, obs)
            region = stepper.step(region, flow, nextFlow, obs, policy)
            flow = nextFlow
            t += 1000L
        }
        return region
    }

    // =========================================================================
    // The three fielded shapes
    // =========================================================================

    @Test
    fun `a mid-spin read is parked, and the settled value commits on its second frame`() {
        // 470.00 is well-formed and wrong; 16.70 is the real total, seen twice.
        val result = feed(region(), 470.00, 16.70, 16.70)
        assertEquals(16.70, result.session?.runningEarnings)
        assertNull("the park is spent once it commits", result.pendingSessionPayQuote)
    }

    @Test
    fun `a mid-spin read never reaches runningEarnings on its own frame`() {
        val afterSpin = feed(region(runningEarnings = 16.70), 470.00)
        assertEquals(
            "one sighting must not move the committed figure",
            16.70, afterSpin.session?.runningEarnings,
        )
        assertEquals(470.00, afterSpin.pendingSessionPayQuote)
    }

    @Test
    fun `a real change commits on the second frame that agrees`() {
        val afterFirst = feed(region(runningEarnings = 16.70), 25.20)
        assertEquals(16.70, afterFirst.session?.runningEarnings)

        val afterSecond = feed(region(runningEarnings = 16.70), 25.20, 25.20)
        assertEquals(25.20, afterSecond.session?.runningEarnings)
        assertNull(afterSecond.pendingSessionPayQuote)
    }

    @Test
    fun `a flicker away and back never commits the flicker`() {
        // The wheel spins through 470 between two settled 16.70 reads.
        val result = feed(region(runningEarnings = 16.70), 470.00, 16.70)
        assertEquals(16.70, result.session?.runningEarnings)
        assertNull(
            "a read that equals the committed total means the wheel is at rest — drop the park",
            result.pendingSessionPayQuote,
        )
    }

    @Test
    fun `two DIFFERENT spin values never commit either of them`() {
        val result = feed(region(runningEarnings = 16.70), 470.00, 580.00, 70.00)
        assertEquals(16.70, result.session?.runningEarnings)
        assertEquals("the newest sighting is the one parked", 70.00, result.pendingSessionPayQuote)
    }

    // =========================================================================
    // Absence and lifecycle
    // =========================================================================

    @Test
    fun `a null read leaves both the committed figure and the park alone`() {
        val result = feed(region(runningEarnings = 16.70, pending = 25.20), null)
        assertEquals(16.70, result.session?.runningEarnings)
        assertEquals(25.20, result.pendingSessionPayQuote)
    }

    @Test
    fun `a first read on a fresh session commits only on repetition`() {
        assertEquals(0.0, feed(region(), 16.70).session?.runningEarnings)
        assertEquals(16.70, feed(region(), 16.70, 16.70).session?.runningEarnings)
    }

    @Test
    fun `the park is cleared when the session ends`() {
        var flow = FlowRegion(flow = Flow.Idle)
        var region = region(runningEarnings = 16.70, pending = 470.00)

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
            region.pendingSessionPayQuote,
        )
    }

    @Test
    fun `the park is cleared when a NEW session starts`() {
        // A read parked against the previous dash must never settle against this one.
        val offline = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Offline,
            pendingSessionPayQuote = 470.00,
        )
        val obs = idleScreen(sessionPay = null, timestamp = 9_000L)
        val nextFlow = flowStepper.step(FlowRegion(), obs)
        val started = stepper.step(offline, FlowRegion(), nextFlow, obs, policy)

        assertEquals("a session must have been minted", 9_000L, started.session?.startedAt)
        assertNull(started.pendingSessionPayQuote)
    }
}
