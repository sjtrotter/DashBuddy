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
        val park = PendingSessionPay(16.70, t0, t0 + settle)
        val armed = diff(region(), region(pending = park))
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(settle, armed.durationMs)
        assertEquals(Platform.DoorDash, armed.platform)
    }

    @Test
    fun `a replaced park re-arms on the new deadline`() {
        val old = PendingSessionPay(470.00, t0 - 200L, t0 - 200L + settle)
        val new = PendingSessionPay(16.70, t0, t0 + settle)
        val armed = diff(region(pending = old), region(pending = new))
            .filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(settle, armed.durationMs)
    }

    @Test
    fun `the same park re-observed does not re-arm - the window is never extended`() {
        val park = PendingSessionPay(16.70, t0 - 500L, t0 - 500L + settle)
        val effects = diff(region(pending = park), region(pending = park))

        assertTrue(
            "an unchanged deadline must not schedule anything",
            effects.filterIsInstance<AppEffect.ScheduleTimeout>()
                .none { it.type == TimeoutType.SESSION_PAY_SETTLE },
        )
    }

    @Test
    fun `a cleared park cancels the settle timer`() {
        val park = PendingSessionPay(16.70, t0 - 500L, t0 - 500L + settle)
        val cancelled = diff(region(pending = park), region())
            .filterIsInstance<AppEffect.CancelTimeout>()
            .single { it.type == TimeoutType.SESSION_PAY_SETTLE }

        assertEquals(Platform.DoorDash, cancelled.platform)
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
        val park = PendingSessionPay(16.70, t0 - 500L, t0 - 500L + settle)

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
