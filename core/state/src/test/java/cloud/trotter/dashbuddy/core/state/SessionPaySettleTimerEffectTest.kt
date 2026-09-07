package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingModeResume
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `SESSION_PAY_SETTLE` wake timer (#1029) — `EffectMap.diffSessionPaySettleTimer`.
 *
 * This timer is load-bearing, not merely punctual: `FrameGate` identity dedup
 * (`IdleFields.dedupeHash` folds in `sessionPay`) drops every repeat of an unchanged wheel read, so
 * on a settled total NO further screen observation reaches the machine and the stepper's lazy
 * expiry has nothing to ride in on. Without this arm the parked figure would never commit.
 */
class SessionPaySettleTimerEffectTest {

    private val effectMap = EffectMap()
    private val settle = GraceConfig.SESSION_PAY_SETTLE_MS
    private val t0 = 10_000L

    private fun region(
        pending: PendingSessionPay? = null,
        destructive: PendingDestructive? = null,
        modeResume: PendingModeResume? = null,
    ) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        pendingSessionPay = pending,
        pendingDestructive = destructive,
        pendingModeResume = modeResume,
    )

    private fun state(region: PlatformRegion) =
        AppState(regions = Regions(platforms = mapOf(Platform.DoorDash to region)))

    private val obs = Observation.Screen(
        timestamp = t0,
        captureId = null,
        ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Online,
        parsed = ParsedFields.IdleFields(sessionPay = 16.70),
    )

    private fun diff(prev: PlatformRegion, next: PlatformRegion): List<AppEffect> =
        effectMap.diff(state(prev), state(next), obs)

    @Test
    fun `a park appearing arms the settle timer for exactly its remaining window`() {
        val park = PendingSessionPay(16.70, t0, t0 + settle, Flow.Idle)
        val armed = diff(region(), region(pending = park))
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(settle, armed.durationMs)
        assertEquals(Platform.DoorDash, armed.platform)
    }

    @Test
    fun `a replaced park re-arms on the new generation`() {
        val old = PendingSessionPay(470.00, t0 - 200L, t0 - 200L + settle, Flow.Idle, wakeId = 1L)
        val new = PendingSessionPay(16.70, t0, t0 + settle, Flow.Idle, wakeId = 2L)
        val armed = diff(region(pending = old), region(pending = new))
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(settle, armed.durationMs)
        assertEquals(ObservationPayload.GraceWake(2L), armed.payload)
    }

    @Test
    fun `a replacement with the SAME deadline still re-arms - the generation changed`() {
        // #1054 round 5. Round 4 armed on a deadline CHANGE, so this pair emitted nothing and the
        // superseded coroutine stayed live to commit the replacement. Two successive parks really
        // can share a deadline: after a clock step-back the replacement computes the identical
        // `now + settleWindow`.
        val old = PendingSessionPay(20.00, t0, t0 + settle, Flow.Idle, wakeId = 1L)
        val new = PendingSessionPay(30.00, t0, t0 + settle, Flow.Idle, wakeId = 2L)
        val armed = diff(region(pending = old), region(pending = new))
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(ObservationPayload.GraceWake(2L), armed.payload)
    }

    @Test
    fun `the same park re-observed does not re-arm - the window is never extended`() {
        val park = PendingSessionPay(16.70, t0 - 500L, t0 - 500L + settle, Flow.Idle, wakeId = 1L)
        val effects = diff(region(pending = park), region(pending = park))

        assertTrue(
            "an unchanged deadline must not schedule anything",
            effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .none { it.type == TimeoutType.SESSION_PAY_SETTLE },
        )
    }

    @Test
    fun `a cleared park cancels the settle timer`() {
        val park = PendingSessionPay(16.70, t0 - 500L, t0 - 500L + settle, Flow.Idle)
        val cancelled = diff(region(pending = park), region())
            .filterIsInstance<AppEffect.CancelTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(Platform.DoorDash, cancelled.platform)
    }

    @Test
    fun `an early own-wake COMMITS the park, so no re-arm is needed`() {
        // #1029 S5 added an early-wake RE-ARM here: the timer is armed for exactly
        // `deadline - obs.timestamp` but its fire is stamped with the wall clock, so a clock
        // step-back made it land early, and a no-op fire would strand the park forever (FrameGate
        // identity dedup means no frame is coming to retry). #1054 round 4 fixed that at the root
        // instead — the arm carries `GraceWake(deadline)`, so the expiry recognises the fire
        // whenever it lands and there is nothing left to rescue. The re-arm branch is deleted, and
        // this test pins the COMMIT (Astra: the old shape fed identical states to `EffectMap` and
        // proved only that no re-arm was emitted — it never showed the park landing). So drive the
        // real machine and read the money.
        val park = PendingSessionPay(16.70, t0, t0 + settle, Flow.Idle, wakeId = 9L)
        val machine = StateMachine(
            FlowRegionStepper(), PlatformRegionStepper(),
            CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap(),
        )
        val wake = Observation.Timeout(
            timestamp = t0 + settle - 1_000L,
            type = TimeoutType.SESSION_PAY_SETTLE,
            targetPlatform = Platform.DoorDash,
            payload = ObservationPayload.GraceWake(9L),
        )

        val transition = machine.step(
            AppState(
                regions = Regions(
                    flow = FlowRegion(flow = Flow.Idle, activePlatform = Platform.DoorDash),
                    platforms = mapOf(Platform.DoorDash to region(pending = park)),
                ),
            ),
            wake,
        )
        val dd = transition.newState.regions.platforms.getValue(Platform.DoorDash)

        assertEquals(
            "the park committed on its own wake, a full second 'early' by the wall clock",
            16.70,
            dd.session?.runningEarnings ?: Double.NaN,
            0.0001,
        )
        assertNull("and the park is consumed", dd.pendingSessionPay)
        assertTrue(
            "nothing is re-armed — identity made a rescue unnecessary",
            transition.effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .none { it.type == TimeoutType.SESSION_PAY_SETTLE },
        )
    }

    @Test
    fun `an arm carries its generation as identity and its deadline as an absolute instant`() {
        // The two halves of #1054, both on one effect: the `GraceWake` payload is what the expiry
        // matches on (so a superseded fire is inert), and `deadlineMs` is what the engine waits on
        // (so a tail-REPLAYED arm lands on time rather than a full window late).
        val park = PendingSessionPay(16.70, t0, t0 + settle, Flow.Idle, wakeId = 4L)
        val armed = diff(region(), region(pending = park))
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(ObservationPayload.GraceWake(4L), armed.payload)
        assertEquals(t0 + settle, armed.deadlineMs)
    }

    @Test
    fun `an unrelated timer's fire does not re-arm the settle timer`() {
        val park = PendingSessionPay(16.70, t0, t0 + settle, Flow.Idle)
        val wake = Observation.Timeout(
            timestamp = t0 + 100L,
            type = TimeoutType.GRACE_COMMIT,
            targetPlatform = Platform.DoorDash,
        )
        assertTrue(
            "only the settle timer's OWN fire re-arms it",
            effectMap.diff(state(region(pending = park)), state(region(pending = park)), wake)
                .filterIsInstance<AppEffect.ScheduleTimeout>()
                .none { it.type == TimeoutType.SESSION_PAY_SETTLE },
        )
    }

    @Test
    fun `clearing the park never cross-cancels the other graces in the same region`() {
        // The whole reason SESSION_PAY_SETTLE is its own TimeoutType (#438 item 1): all three
        // graces live on the SAME platform region, so a shared type's (type, platform) timer key
        // would make one cancel the others.
        val destructive = PendingDestructive(
            kind = DestructiveKind.TASK_RETIRE,
            since = t0 - 1_000L,
            deadline = t0 + 60_000L,
        )
        val modeResume = PendingModeResume(since = t0 - 1_000L, deadline = t0 + 60_000L)
        val park = PendingSessionPay(16.70, t0 - 500L, t0 - 500L + settle, Flow.Idle)

        val effects = diff(
            region(pending = park, destructive = destructive, modeResume = modeResume),
            region(destructive = destructive, modeResume = modeResume),
        )
        val cancelled = effects.filterIsInstance<AppEffect.CancelTimeout>().map { it.type }

        assertTrue("the settle timer is the one cancelled", TimeoutType.SESSION_PAY_SETTLE in cancelled)
        assertTrue("the destructive grace timer survives", TimeoutType.GRACE_COMMIT !in cancelled)
        assertTrue("the resume grace timer survives", TimeoutType.MODE_RESUME_COMMIT !in cancelled)
    }
}
