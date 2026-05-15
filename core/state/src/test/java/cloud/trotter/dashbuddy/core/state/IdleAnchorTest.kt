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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [PlatformRegion.idleEnteredAt] lifecycle management
 * in [PlatformRegionStepper].
 */
class IdleAnchorTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun screen(
        flow: Flow?,
        modeHint: Mode? = null,
        timestamp: Long = 1000L,
        ruleId: String = "doordash.screen.test",
    ) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = modeHint,
        parsed = ParsedFields.None,
    )

    private fun onlineRegion(
        idleEnteredAt: Long? = null,
        timestamp: Long = 500L,
    ) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session(sessionId = "test-session", startedAt = 100L),
        lastObservedAt = timestamp,
        idleEnteredAt = idleEnteredAt,
    )

    private fun offlineRegion() = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Offline,
    )

    private fun step(
        prev: PlatformRegion,
        obs: Observation,
        prevFlow: FlowRegion = FlowRegion(),
    ): PlatformRegion {
        val nextFlow = if (obs is Observation.FlowObservation) {
            flowStepper.step(prevFlow, obs)
        } else prevFlow
        return stepper.step(prev, prevFlow, nextFlow, obs, policy)
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    fun `idleEnteredAt set when flow enters Idle while Online`() {
        val obs = screen(flow = Flow.Idle, modeHint = Mode.Online, timestamp = 1000L)
        val result = step(onlineRegion(), obs)
        assertEquals(1000L, result.idleEnteredAt)
    }

    @Test
    fun `idleEnteredAt cleared when flow leaves Idle`() {
        val prev = onlineRegion(idleEnteredAt = 800L)
        val prevFlow = FlowRegion(flow = Flow.Idle)
        val obs = screen(flow = Flow.OfferPresented, timestamp = 1200L)
        val result = step(prev, obs, prevFlow)
        assertNull(result.idleEnteredAt)
    }

    @Test
    fun `idleEnteredAt not set when Offline`() {
        val obs = screen(flow = Flow.Idle, modeHint = Mode.Offline, timestamp = 1000L)
        val result = step(offlineRegion(), obs)
        assertNull(result.idleEnteredAt)
    }

    @Test
    fun `idleEnteredAt preserved through repeated Idle observations`() {
        val prev = onlineRegion(idleEnteredAt = 800L)
        val prevFlow = FlowRegion(flow = Flow.Idle)
        val obs = screen(flow = Flow.Idle, modeHint = Mode.Online, timestamp = 1200L)
        val result = step(prev, obs, prevFlow)
        // Should keep the original timestamp, not overwrite with the new one
        assertEquals(800L, result.idleEnteredAt)
    }

    @Test
    fun `idleEnteredAt cleared on session end`() {
        val prev = onlineRegion(idleEnteredAt = 800L)
        val prevFlow = FlowRegion(flow = Flow.Idle)
        val obs = screen(flow = Flow.SessionEnded, modeHint = Mode.Offline, timestamp = 1500L)
        val result = step(prev, obs, prevFlow)
        assertNull(result.idleEnteredAt)
    }

    @Test
    fun `idleEnteredAt reset after leaving and re-entering Idle`() {
        // Start idle at 800
        val prev = onlineRegion(idleEnteredAt = 800L)
        val idleFlow = FlowRegion(flow = Flow.Idle)

        // Leave idle (offer arrives)
        val offerObs = screen(flow = Flow.OfferPresented, timestamp = 1000L)
        val afterOffer = step(prev, offerObs, idleFlow)
        assertNull(afterOffer.idleEnteredAt)

        // Re-enter idle at 1500
        val offerFlow = FlowRegion(flow = Flow.OfferPresented)
        val idleObs = screen(flow = Flow.Idle, modeHint = Mode.Online, timestamp = 1500L)
        val afterIdle = step(afterOffer, idleObs, offerFlow)
        assertEquals(1500L, afterIdle.idleEnteredAt)
    }

    @Test
    fun `idleEnteredAt cleared when mode transitions to Paused`() {
        val prev = onlineRegion(idleEnteredAt = 800L)
        val prevFlow = FlowRegion(flow = Flow.Idle)
        val obs = screen(flow = Flow.Idle, modeHint = Mode.Paused, timestamp = 1200L)
        val result = step(prev, obs, prevFlow)
        // Paused is not Online, so idle anchor should be cleared
        // (the early return for Offline won't fire, but the mode changes to Paused
        // and Flow.Idle + Paused mode should not have an idle anchor)
        assertNull(result.idleEnteredAt)
    }
}
