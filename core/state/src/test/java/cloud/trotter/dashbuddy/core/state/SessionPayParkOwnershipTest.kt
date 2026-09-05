package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A parked running-total read is owned by **(flow, PLATFORM)** — #1029 review round 4.
 *
 * The flow half alone is not enough, and the reason is structural rather than hypothetical:
 * `StateMachine.stepPlatforms` steps ONLY `obs.platform`'s region, so an Uber frame never reaches a
 * DoorDash `PlatformRegion` at all. R0, by contrast, is SHARED — the Uber frame moves it. A DoorDash
 * park therefore survives untouched while the surface it was read from leaves the screen, and its
 * own `SESSION_PAY_SETTLE` wake timer (which IS routed at DoorDash) then runs the lazy expiry and
 * commits a mid-spin figure. Worse, two idle screens on two platforms defeat a flow-only test
 * outright: R0 stays `Flow.Idle` throughout, so the flow never "changes" at all.
 *
 * `FlowRegion.activePlatform` is exactly the missing fact — `FlowRegionStepper` stamps it on every
 * flow-bearing frame and leaves R0 untouched on Timeout/UiInput/Loopback — so a NON-flow
 * observation checks ownership BEFORE the expiry and drops a park it no longer owns.
 *
 * These tests drive the REAL [StateMachine] rather than the stepper directly, because the routing
 * (`stepPlatforms` skipping the other platform's region while R0 moves) is the whole point.
 *
 * **#1052 — ownership on BOTH sides.** Checking only the RESULTING R0 leaves the departure
 * unrecorded whenever it happened on a frame this region was not stepped for, and the last three
 * cases below are the reachable consequences: the owner returning with the SAME value (which
 * `settleSessionPay` deliberately does not re-park, so the ORIGINAL deadline survives the
 * interlude), a null-pay return landing past that deadline, and a flow-LESS own-platform
 * observation, which is a `FlowObservation` and therefore skipped the round-4 non-flow guard while
 * being just as incapable of putting the pill back on screen. The stepper now also requires
 * ownership on `prevFlow` and orders a flow-less observation like a timer; the control case proves
 * the rule is about ownership rather than the observation's kind.
 */
class SessionPayParkOwnershipTest {

    private lateinit var machine: StateMachine

    private val settle = GraceConfig.SESSION_PAY_SETTLE_MS
    private val t0 = 10_000L

    @Before
    fun setUp() {
        machine = StateMachine(
            flowStepper = FlowRegionStepper(),
            platformStepper = PlatformRegionStepper(),
            crossPlatformStepper = CrossPlatformRegionStepper(),
            transitionPolicy = TransitionPolicy(),
            effectMap = EffectMap(),
        )
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun idle(
        ruleId: String,
        sessionPay: Double?,
        timestamp: Long,
    ) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = ruleId,
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Online,
        parsed = ParsedFields.IdleFields(sessionPay = sessionPay),
    )

    /** An Uber frame that moves R0 to a different flow AND a different active platform. */
    private fun uberOffer(timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "uber.screen.offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.OfferPresented,
        modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    /** An Uber frame on the SAME flow the DoorDash park was made on — only the platform differs. */
    private fun uberIdle(timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = null,
        ruleId = "uber.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    /**
     * A DoorDash push carrying NO flow — the second bypass #1052 names. It is a `FlowObservation`,
     * so the #1029 `obs !is FlowObservation` guard let it take the expire-FIRST path even while
     * another platform owned R0; it is nonetheless incapable of being the departure frame, because
     * `FlowRegionStepper` leaves R0's flow and `activePlatform` untouched for a flow-less one.
     */
    private fun flowlessNotification(timestamp: Long) = Observation.Notification(
        timestamp = timestamp,
        captureId = null,
        ruleId = "doordash.notification.demand_nudge",
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
    )

    private fun settleTimer(timestamp: Long) = Observation.Timeout(
        timestamp = timestamp,
        type = TimeoutType.SESSION_PAY_SETTLE,
        targetPlatform = Platform.DoorDash,
    )

    private fun AppState.step(vararg obs: Observation): AppState =
        obs.fold(this) { acc, o -> machine.step(acc, o).newState }

    private val AppState.doorDash: PlatformRegion?
        get() = regions.platforms[Platform.DoorDash]

    /** A live DoorDash dash with $16.70 committed and a $470.00 mid-spin read parked at [t0]. */
    private fun parkedState(): AppState {
        // The first idle frame mints the session; the second (16.70) settles the committed figure
        // via its own window; then 470.00 parks. Doing it through the machine keeps R0 honest.
        var state = AppState().step(
            idle("doordash.screen.waiting_for_offer", 16.70, t0 - (2 * settle)),
            settleTimer(t0 - settle),
            idle("doordash.screen.waiting_for_offer", 470.00, t0),
        )
        val pend = state.doorDash?.pendingSessionPay
        assertNotNull("the fixture must actually park a read", pend)
        assertEquals(470.00, pend!!.value, 0.0001)
        assertEquals(Flow.Idle, pend.flow)
        assertEquals(
            "the fixture's committed figure is the real total",
            16.70,
            state.doorDash?.session?.runningEarnings ?: Double.NaN,
            0.0001,
        )
        assertEquals(Platform.DoorDash, state.regions.flow.activePlatform)
        return state
    }

    private fun assertEarnings(expected: Double, state: AppState, message: String = "") {
        assertEquals(
            message,
            expected,
            state.doorDash?.session?.runningEarnings ?: Double.NaN,
            0.0001,
        )
    }

    // =========================================================================
    // The cross-platform interloper
    // =========================================================================

    @Test
    fun `an Uber frame on another flow drops the DoorDash park before its timer can commit`() {
        val after = parkedState().step(
            uberOffer(t0 + 500L),
            settleTimer(t0 + settle),
        )

        assertEarnings(16.70, after, "a figure R0 stopped showing must never commit")
        assertNull("the park lost its surface", after.doorDash?.pendingSessionPay)
    }

    @Test
    fun `an Uber IDLE frame drops it too - the flow alone cannot tell them apart`() {
        // R0 stays Flow.Idle across this interlude, so a flow-only ownership test is blind here.
        // The park is still gone: the surface on screen belongs to another platform.
        val after = parkedState().step(
            uberIdle(t0 + 500L),
            settleTimer(t0 + settle),
        )

        assertEquals(
            "the premise: R0's flow never moved",
            Flow.Idle,
            after.regions.flow.flow,
        )
        assertEquals(Platform.Uber, after.regions.flow.activePlatform)
        assertEarnings(16.70, after, "the mid-spin $470 must not commit")
        assertNull(after.doorDash?.pendingSessionPay)
    }

    @Test
    fun `with no interloper the same timer commits - the control`() {
        val after = parkedState().step(settleTimer(t0 + settle))

        assertEarnings(470.00, after, "an unchallenged read on its own surface commits")
        assertNull(after.doorDash?.pendingSessionPay)
    }

    @Test
    fun `a DoorDash frame after the Uber interlude re-parks normally`() {
        // Dropping the park is fail-null, not a latch: returning to the pill re-parks the live read
        // with a fresh window, and that frame IS admitted (its observation identity differs from
        // the interloper's).
        val returned = parkedState().step(
            uberIdle(t0 + 500L),
            idle("doordash.screen.waiting_for_offer", 24.90, t0 + 1_000L),
        )

        val pend = returned.doorDash?.pendingSessionPay
        assertNotNull("the returning frame must re-park", pend)
        assertEquals(24.90, pend!!.value, 0.0001)
        assertEquals(t0 + 1_000L, pend.since)
        assertEquals(t0 + 1_000L + settle, pend.deadline)
        assertEarnings(16.70, returned, "still nothing committed inside the new window")

        val committed = returned.step(settleTimer(t0 + 1_000L + settle))
        assertEarnings(24.90, committed)
    }

    // =========================================================================
    // #1052 — ownership must hold BEFORE the observation as well as after
    // =========================================================================

    @Test
    fun `the owner returning with the SAME value re-parks with a fresh deadline, it does not resume`() {
        // A1, the sequence the round-4 return case could not see because it returned a DIFFERENT
        // value (which replaces the park and re-arms the timer regardless). With the same value,
        // `settleSessionPay`'s "same read again" arm KEEPS the park and its ORIGINAL deadline — so
        // checking ownership only on the RESULTING R0 reads "owned" again and the original timer
        // commits a figure that was off screen for the whole interlude. The departure has to be
        // recorded when the owner is next stepped, which is what `ownedBefore` does.
        val returned = parkedState().step(
            uberIdle(t0 + 500L),
            idle("doordash.screen.waiting_for_offer", 470.00, t0 + 2_500L),
        )

        val pend = returned.doorDash?.pendingSessionPay
        assertNotNull("the returning frame parks its read", pend)
        assertEquals(470.00, pend!!.value, 0.0001)
        assertEquals("the park is FRESH, not the survivor", t0 + 2_500L, pend.since)
        assertEquals(t0 + 2_500L + settle, pend.deadline)

        // The original deadline is t0 + settle. Nothing may commit there any more.
        val atOldDeadline = returned.step(settleTimer(t0 + settle))
        assertEarnings(
            16.70,
            atOldDeadline,
            "the pre-interlude deadline belongs to a park that no longer exists",
        )

        // It commits only after standing its OWN full window back on its own surface.
        val committed = atOldDeadline.step(settleTimer(t0 + 2_500L + settle))
        assertEarnings(470.00, committed, "a full window on its own surface after the return")
        assertNull(committed.doorDash?.pendingSessionPay)
    }

    @Test
    fun `a null-pay return past the old deadline commits nothing`() {
        // The same interlude, but the returning frame carries no running total at all — so there
        // is no read to contradict the park and, without `ownedBefore`, the lazy expiry on that
        // very frame would commit $470 outright.
        val returned = parkedState().step(
            uberIdle(t0 + 500L),
            idle("doordash.screen.waiting_for_offer", null, t0 + settle + 1L),
        )

        assertEarnings(16.70, returned, "an absent read is not a confirmation")
        assertNull("nothing survives the interlude to be committed", returned.doorDash?.pendingSessionPay)
    }

    @Test
    fun `a flow-less DoorDash push cannot commit a park another platform's screen displaced`() {
        // The second A1 bypass: a `Notification` with `flow = null` IS a `FlowObservation`, so the
        // round-4 non-flow guard did not apply and it took the expire-first path.
        val after = parkedState().step(
            uberIdle(t0 + 500L),
            flowlessNotification(t0 + settle),
        )

        assertEarnings(16.70, after, "a push is not evidence that the pill is back on screen")
        assertNull(after.doorDash?.pendingSessionPay)
    }

    @Test
    fun `a flow-less DoorDash push DOES ride the expiry while DoorDash still owns R0 - the control`() {
        // With no interloper the push is an ordinary observation past the deadline: it does not
        // move R0, ownership held before and after, and the park lands exactly as the wake timer
        // would have landed it. The rule is about ownership, not about the observation's kind.
        val after = parkedState().step(flowlessNotification(t0 + settle))

        assertEarnings(470.00, after, "an unchallenged read on its own surface commits")
        assertNull(after.doorDash?.pendingSessionPay)
    }
}
