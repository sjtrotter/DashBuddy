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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1054 part 2 — [pendingDeadlineTimers], the ONE enumeration of "what did the recovery restore
 * that is still waiting on a clock".
 *
 * Pure and platform-agnostic: it states deadlines and never durations (a duration is a wall-clock
 * question, answered at the `StateManagerV2` effect boundary), and every platform it names comes
 * from the region itself.
 *
 * The deliberate omission is the settle park — see [droppingSessionPayParks]. A park is stale
 * EVIDENCE and is dropped; a grace is a COMMITMENT in flight and is re-armed. The two rules are
 * complements, and this test pins that they stay complements.
 */
class PendingDeadlineTimersTest {

    private fun region(
        platform: Platform,
        destructive: PendingDestructive? = null,
        modeResume: PendingModeResume? = null,
        park: PendingSessionPay? = null,
    ) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = Session("s-${platform.wire}", startedAt = 100L),
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
    fun `a restored resume grace enumerates its MODE_RESUME_COMMIT deadline`() {
        assertEquals(
            listOf(PendingDeadline(TimeoutType.MODE_RESUME_COMMIT, Platform.DoorDash, 19_000L)),
            state(region(Platform.DoorDash, modeResume = modeResume)).pendingDeadlineTimers(),
        )
    }

    @Test
    fun `both graces on one region enumerate separately - they are separate timer keys`() {
        // Both graces live on the SAME platform region, which is exactly why MODE_RESUME_COMMIT is
        // its own TimeoutType (#605): under a shared type the (type, platform) timer key would make
        // one re-arm cancel the other.
        val enumerated = state(
            region(Platform.DoorDash, destructive = destructive, modeResume = modeResume),
        ).pendingDeadlineTimers()

        assertEquals(2, enumerated.size)
        assertEquals(
            setOf(TimeoutType.GRACE_COMMIT, TimeoutType.MODE_RESUME_COMMIT),
            enumerated.map { it.type }.toSet(),
        )
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
    fun `each platform's pendings carry that platform, never a literal`() {
        val enumerated = state(
            region(Platform.DoorDash, destructive = destructive),
            region(Platform.Uber, modeResume = modeResume),
        ).pendingDeadlineTimers()

        assertEquals(
            setOf(
                PendingDeadline(TimeoutType.GRACE_COMMIT, Platform.DoorDash, 20_500L),
                PendingDeadline(TimeoutType.MODE_RESUME_COMMIT, Platform.Uber, 19_000L),
            ),
            enumerated.toSet(),
        )
    }
}
