package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
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
    fun `a replaced park re-arms on the new deadline`() {
        val old = PendingSessionPay(470.00, t0 - 200L, t0 - 200L + settle, Flow.Idle)
        val new = PendingSessionPay(16.70, t0, t0 + settle, Flow.Idle)
        val armed = diff(region(pending = old), region(pending = new))
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(settle, armed.durationMs)
    }

    @Test
    fun `the same park re-observed does not re-arm - the window is never extended`() {
        val park = PendingSessionPay(16.70, t0 - 500L, t0 - 500L + settle, Flow.Idle)
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
    fun `an early or stale fire of the settle timer RE-ARMS for the remainder`() {
        // #1029 S5: the wake timer's duration is exactly `deadline - obs.timestamp`, but the fired
        // observation is stamped with the wall clock — so a fire can land AT or before the
        // deadline. Because no frame is coming to retry (FrameGate identity dedup), a no-op fire
        // would strand the park forever. Re-arm, never commit: a stale fire from a park that has
        // since been REPLACED must not commit the new one early — that is a mid-spin value.
        val park = PendingSessionPay(16.70, t0, t0 + settle, Flow.Idle)
        val wake = Observation.Timeout(
            timestamp = t0 + settle - 1_000L,
            type = TimeoutType.SESSION_PAY_SETTLE,
            targetPlatform = Platform.DoorDash,
        )
        val armed = effectMap.diff(state(region(pending = park)), state(region(pending = park)), wake)
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(1_000L, armed.durationMs)
    }

    @Test
    fun `a fire AT or PAST the deadline never re-arms - a frozen park would spin at the 1ms floor`() {
        // #1052 round 3: a park is FROZEN while the dash is not Online, so its own wake timer can
        // now land at or past the deadline and leave the pending standing with an UNCHANGED
        // deadline — a shape that previously only ever meant "the park committed", i.e. the cancel
        // arm. Re-arming there would schedule `(deadline - now).coerceAtLeast(1)` = 1 ms and fire
        // again immediately, for as long as the dasher stayed paused.
        val park = PendingSessionPay(16.70, t0, t0 + settle, Flow.Idle)
        for (fireAt in listOf(t0 + settle, t0 + settle + 5_000L)) {
            val wake = Observation.Timeout(
                timestamp = fireAt,
                type = TimeoutType.SESSION_PAY_SETTLE,
                targetPlatform = Platform.DoorDash,
            )
            assertTrue(
                "a wake at $fireAt must not re-arm the frozen park",
                effectMap.diff(state(region(pending = park)), state(region(pending = park)), wake)
                    .filterIsInstance<AppEffect.ScheduleTimeout>()
                    .none { it.type == TimeoutType.SESSION_PAY_SETTLE },
            )
        }
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
