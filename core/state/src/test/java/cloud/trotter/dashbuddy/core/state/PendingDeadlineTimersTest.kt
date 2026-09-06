package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
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
 * #1054 part 2 — [pendingDeadlineTimers], the enumeration of what crash recovery RE-ARMS.
 *
 * Since round 3 that is the destructive grace and nothing else. Everything else deadline-bearing is
 * either dropped as stale evidence by [recoveryHygiene] (the settle park, the graced resume) or a
 * pre-existing gap this issue does not close (`OFFER_EXPIRY`, `SESSION_PAUSED_SAFETY`, `SETTLE_UI`
 * — tracked as #1076). These tests pin the boundary in both directions, because the tempting
 * mistake in either is silent: enumerate one more and a phantom dash gets minted, enumerate one
 * fewer and a dash never ends.
 *
 * Pure and platform-agnostic: it states deadlines and never durations (a duration is a wall-clock
 * question, answered where the timer is actually scheduled), and every platform it names comes from
 * the region itself.
 */
class PendingDeadlineTimersTest {

    private fun region(
        platform: Platform,
        destructive: PendingDestructive? = null,
        modeResume: PendingModeResume? = null,
        park: PendingSessionPay? = null,
        session: Session? = Session("live", startedAt = 100L),
    ) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = session,
        pendingDestructive = destructive,
        pendingModeResume = modeResume,
        pendingSessionPay = park,
    )

    private fun state(vararg regions: PlatformRegion) = AppState(
        regions = Regions(platforms = regions.associateBy { it.platform }),
    )

    private val destructive = PendingDestructive(
        kind = DestructiveKind.SESSION_END, since = 10_500L, deadline = 20_500L,
    )
    private val modeResume = PendingModeResume(since = 11_000L, deadline = 19_000L)
    private val park = PendingSessionPay(16.70, 12_000L, 15_000L, Flow.Idle)

    @Test
    fun `a state with no pendings enumerates nothing`() {
        assertTrue(state(region(Platform.DoorDash)).pendingDeadlineTimers().isEmpty())
        assertTrue("and an empty state is total, not an error", AppState().pendingDeadlineTimers().isEmpty())
    }

    @Test
    fun `a restored destructive grace enumerates its GRACE_COMMIT deadline`() {
        assertEquals(
            listOf(PendingDeadline(TimeoutType.GRACE_COMMIT, Platform.DoorDash, 20_500L)),
            state(region(Platform.DoorDash, destructive = destructive)).pendingDeadlineTimers(),
        )
    }

    @Test
    fun `a graced resume is NEVER enumerated, with a session or without one`() {
        // #1054 round 3. Round 2 guarded this on `session != null`, reasoning that a session-less
        // resume would MINT a phantom dash (`applyModeTransition(…, Online)` mints when there is
        // none) while one with a live session was a real commitment. Both halves were wrong: the
        // guard suppressed only the RE-ARM, leaving the resume installed for the tail's own
        // replayed timer or any later observation to commit; and even with a live session, a
        // resume's window is 8 s of UN-CONTRADICTED observation, so committing it after a restart
        // asserts that dead process time was nobody contradicting it — and the commit CANCELS the
        // `SESSION_PAUSED_SAFETY` net on its way through `diffMode`. [recoveryHygiene] drops it
        // outright now; there is nothing left here to enumerate either way.
        for (session in listOf(Session("live", startedAt = 100L), null)) {
            assertTrue(
                "a resume is evidence, not a commitment (session = $session)",
                state(region(Platform.DoorDash, modeResume = modeResume, session = session))
                    .pendingDeadlineTimers().isEmpty(),
            )
        }
    }

    @Test
    fun `a restored settle park is NOT enumerated - it is dropped, not re-armed`() {
        assertTrue(
            "a park is pre-crash evidence; re-arming its timer would wake a figure nothing can " +
                "contradict — the drop rule owns it",
            state(region(Platform.DoorDash, park = park)).pendingDeadlineTimers().isEmpty(),
        )
    }

    @Test
    fun `a destructive grace is enumerated even on a SESSION-LESS region`() {
        // The one thing that IS re-armed, and unconditionally. A `SESSION_END` with no session is
        // already a no-op at commit, so the re-arm costs one inert fire at worst — and unlike the
        // other two, its commit fails toward the SAFE side: it ends a dash that in all likelihood
        // really did end, rather than inventing one.
        assertEquals(
            listOf(PendingDeadline(TimeoutType.GRACE_COMMIT, Platform.DoorDash, 20_500L)),
            state(region(Platform.DoorDash, destructive = destructive, session = null))
                .pendingDeadlineTimers(),
        )
    }

    @Test
    fun `a region holding all three pendings enumerates only the destructive grace`() {
        val enumerated = state(
            region(
                Platform.DoorDash,
                destructive = destructive,
                modeResume = modeResume,
                park = park,
            ),
        ).pendingDeadlineTimers()

        assertEquals(
            listOf(PendingDeadline(TimeoutType.GRACE_COMMIT, Platform.DoorDash, 20_500L)),
            enumerated,
        )
    }

    @Test
    fun `each platform's pendings carry that platform, never a literal`() {
        val enumerated = state(
            region(Platform.DoorDash, destructive = destructive),
            region(Platform.Uber, destructive = destructive.copy(deadline = 30_000L)),
        ).pendingDeadlineTimers()

        assertEquals(
            setOf(
                PendingDeadline(TimeoutType.GRACE_COMMIT, Platform.DoorDash, 20_500L),
                PendingDeadline(TimeoutType.GRACE_COMMIT, Platform.Uber, 30_000L),
            ),
            enumerated.toSet(),
        )
    }

    // =====================================================================
    // #1054 round 3 — the hygiene half of the same rule
    // =====================================================================

    @Test
    fun `recoveryHygiene drops the park and the resume on every region, keeping the destructive grace`() {
        val cleaned = state(
            region(Platform.DoorDash, destructive = destructive, modeResume = modeResume, park = park),
            region(Platform.Uber, modeResume = modeResume, park = park),
        ).recoveryHygiene()

        for ((platform, region) in cleaned.regions.platforms) {
            assertNull("the park is stale evidence on $platform", region.pendingSessionPay)
            assertNull("so is the resume on $platform", region.pendingModeResume)
        }
        assertEquals(
            "the decision in flight survives",
            destructive,
            cleaned.regions.platforms.getValue(Platform.DoorDash).pendingDestructive,
        )
    }
}
