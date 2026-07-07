package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.CrossPlatformRegion
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #438 B5 (item 9) — the cross-platform odometer arbitration ([EffectMap.diffCrossPlatform] +
 * [OdometerArbiter]). The four odometer effects moved OFF each platform's session/task diff onto one
 * global decision, so two concurrent sessions no longer collide on one GPS + one anchor:
 *  - Start/Stop on the live-session count crossing 0↔1;
 *  - Pause when EVERY live region is parked, Resume when ANY starts moving.
 */
class OdometerArbitrationTest {

    private val effectMap = EffectMap()

    private fun obs(): Observation = Observation.Screen(
        timestamp = 1_000L,
        captureId = "cap",
        ruleId = "doordash.screen.test",
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = null,
        parsed = ParsedFields.None,
    )

    /** A live region for [platform] whose last-acted flow determines its stationary level. */
    private fun region(platform: Platform, lastActedFlow: Flow?): PlatformRegion =
        PlatformRegion(
            platform = platform,
            mode = Mode.Online,
            session = Session("sess-${platform.wire}", startedAt = 100L),
            lastActedFlow = lastActedFlow,
        )

    private fun state(activeSessionCount: Int, vararg regions: PlatformRegion): AppState =
        AppState(
            regions = Regions(
                crossPlatform = CrossPlatformRegion(activeSessionCount = activeSessionCount),
                platforms = regions.associateBy { it.platform },
            ),
        )

    private fun diff(prev: AppState, next: AppState): List<AppEffect> = effectMap.diff(prev, next, obs())

    // =====================================================================================
    // Start/Stop — the live-session count crossing (dual-session interleave)
    // =====================================================================================

    @Test
    fun `Start emits only on the 0 to 1 crossing`() {
        val prev = state(0)
        val next = state(1, region(Platform.DoorDash, Flow.Idle))
        val effects = diff(prev, next)
        assertEquals("exactly one StartOdometer", 1, effects.count { it is AppEffect.StartOdometer })
        assertFalse("no StopOdometer", effects.any { it is AppEffect.StopOdometer })
    }

    @Test
    fun `a second concurrent session start does NOT re-emit StartOdometer (no zeroing)`() {
        // Count 1→2 as Uber comes online alongside a live DoorDash session. A re-fired Start would
        // reset the odometer and zero DoorDash's accrued miles — the concurrency bug B5 removes. With
        // per-session anchors the second session gets its OWN anchor, untouched by DoorDash's.
        val prev = state(1, region(Platform.DoorDash, Flow.TaskPickupNavigation))
        val next = state(
            2,
            region(Platform.DoorDash, Flow.TaskPickupNavigation),
            region(Platform.Uber, Flow.Idle),
        )
        assertFalse("no StartOdometer on 1→2", diff(prev, next).any { it is AppEffect.StartOdometer })
    }

    @Test
    fun `first session ending while a second is live does NOT emit StopOdometer`() {
        // Count 2→1: DoorDash ends but Uber is still dashing — killing GPS here would strand Uber's
        // odometer mid-drive. Stop must wait for the LAST live session to end.
        val prev = state(
            2,
            region(Platform.DoorDash, Flow.TaskDropoffNavigation),
            region(Platform.Uber, Flow.TaskPickupNavigation),
        )
        val next = state(1, region(Platform.Uber, Flow.TaskPickupNavigation))
        assertFalse("no StopOdometer on 2→1", diff(prev, next).any { it is AppEffect.StopOdometer })
    }

    @Test
    fun `Stop emits only on the 1 to 0 crossing`() {
        val prev = state(1, region(Platform.Uber, Flow.TaskPickupNavigation))
        val next = state(0)
        val effects = diff(prev, next)
        assertEquals("exactly one StopOdometer", 1, effects.count { it is AppEffect.StopOdometer })
        assertFalse("no ResumeOdometer on the count-drop edge", effects.any { it is AppEffect.ResumeOdometer })
    }

    @Test
    fun `ending a dash while parked at a drop stops GPS, never a phantom Resume`() {
        // 1→0 from a stationary region: the naive "arrived→something moving" would read Resume, but
        // the count crossing owns this edge — bothLive is false, so no Pause/Resume can leak.
        val prev = state(1, region(Platform.DoorDash, Flow.TaskDropoffArrived))
        val next = state(0)
        val effects = diff(prev, next)
        assertTrue("StopOdometer", effects.any { it is AppEffect.StopOdometer })
        assertFalse("no ResumeOdometer", effects.any { it is AppEffect.ResumeOdometer })
        assertFalse("no PauseOdometer", effects.any { it is AppEffect.PauseOdometer })
    }

    // =====================================================================================
    // Pause/Resume — the all-live-stationary crossing
    // =====================================================================================

    @Test
    fun `single platform Pause fires on arrival (moving to stationary)`() {
        val prev = state(1, region(Platform.DoorDash, Flow.TaskPickupNavigation))
        val next = state(1, region(Platform.DoorDash, Flow.TaskPickupArrived))
        val effects = diff(prev, next)
        assertTrue("PauseOdometer on arrival", effects.any { it is AppEffect.PauseOdometer })
        assertFalse("no ResumeOdometer", effects.any { it is AppEffect.ResumeOdometer })
    }

    @Test
    fun `single platform Resume fires on leaving an arrived task (stationary to moving)`() {
        val prev = state(1, region(Platform.DoorDash, Flow.TaskPickupArrived))
        val next = state(1, region(Platform.DoorDash, Flow.TaskDropoffNavigation))
        val effects = diff(prev, next)
        assertTrue("ResumeOdometer on departure", effects.any { it is AppEffect.ResumeOdometer })
        assertFalse("no PauseOdometer", effects.any { it is AppEffect.PauseOdometer })
    }

    @Test
    fun `PostTask entry from an arrived drop resumes, from a drive is a no-op`() {
        // Leaving an arrived drop for PostTask is a stationary→moving crossing → Resume.
        val fromArrived = diff(
            state(1, region(Platform.DoorDash, Flow.TaskDropoffArrived)),
            state(1, region(Platform.DoorDash, Flow.PostTask)),
        )
        assertTrue("Resume when PostTask follows an arrived drop", fromArrived.any { it is AppEffect.ResumeOdometer })

        // PostTask following a still-moving drive (no arrival) is NOT a crossing — today's per-edge
        // Resume here was a redundant no-op (GPS already running); the predicate correctly elides it.
        val fromDrive = diff(
            state(1, region(Platform.DoorDash, Flow.TaskDropoffNavigation)),
            state(1, region(Platform.DoorDash, Flow.PostTask)),
        )
        assertFalse("no redundant Resume when PostTask follows a drive", fromDrive.any { it is AppEffect.ResumeOdometer })
    }

    @Test
    fun `Pause requires ALL live regions stationary — one still driving blocks it`() {
        // DoorDash arrives while Uber is still driving: NOT all stationary → no Pause (Uber's drive
        // must keep GPS alive). This is the headline concurrency fix.
        val prev = state(
            2,
            region(Platform.DoorDash, Flow.TaskPickupNavigation),
            region(Platform.Uber, Flow.TaskPickupNavigation),
        )
        val next = state(
            2,
            region(Platform.DoorDash, Flow.TaskPickupArrived),
            region(Platform.Uber, Flow.TaskPickupNavigation),
        )
        assertFalse("no Pause while Uber still drives", diff(prev, next).any { it is AppEffect.PauseOdometer })
    }

    @Test
    fun `Pause fires once BOTH live regions are stationary`() {
        val prev = state(
            2,
            region(Platform.DoorDash, Flow.TaskPickupArrived),
            region(Platform.Uber, Flow.TaskPickupNavigation),
        )
        val next = state(
            2,
            region(Platform.DoorDash, Flow.TaskPickupArrived),
            region(Platform.Uber, Flow.TaskDropoffArrived),
        )
        assertTrue("Pause once both parked", diff(prev, next).any { it is AppEffect.PauseOdometer })
    }

    @Test
    fun `Resume fires when one of two parked regions starts moving again`() {
        val prev = state(
            2,
            region(Platform.DoorDash, Flow.TaskPickupArrived),
            region(Platform.Uber, Flow.TaskDropoffArrived),
        )
        val next = state(
            2,
            region(Platform.DoorDash, Flow.TaskDropoffNavigation),
            region(Platform.Uber, Flow.TaskDropoffArrived),
        )
        assertTrue("Resume when one region leaves its arrival", diff(prev, next).any { it is AppEffect.ResumeOdometer })
    }

    // =====================================================================================
    // Recovery reconciliation (OdometerArbiter.recoveryReconciliation) — the vet M6 seam
    // =====================================================================================

    @Test
    fun `recovery with no live session reconciles to nothing`() {
        val restored = state(0)
        assertTrue(OdometerArbiter.recoveryReconciliation(restored).isEmpty())
    }

    @Test
    fun `recovery mid-drive re-establishes GPS with a Start (the lost-miles fix)`() {
        val restored = state(1, region(Platform.DoorDash, Flow.TaskDropoffNavigation))
        val effects = OdometerArbiter.recoveryReconciliation(restored)
        assertEquals("Start only (moving)", listOf<Class<*>>(AppEffect.StartOdometer::class.java), effects.map { it::class.java })
    }

    @Test
    fun `recovery parked at a drop re-establishes GPS then pauses it`() {
        val restored = state(1, region(Platform.DoorDash, Flow.TaskDropoffArrived))
        val effects = OdometerArbiter.recoveryReconciliation(restored)
        assertEquals(
            "Start then Pause (parked)",
            listOf(AppEffect.StartOdometer::class.java, AppEffect.PauseOdometer::class.java),
            effects.map { it::class.java },
        )
    }
}
