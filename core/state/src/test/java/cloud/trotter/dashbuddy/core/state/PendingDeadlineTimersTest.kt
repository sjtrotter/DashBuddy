package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingModeResume
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
import cloud.trotter.dashbuddy.domain.state.PendingWake
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
        pauseSafety: PendingWake? = null,
    ) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = session,
        pendingDestructive = destructive,
        pendingModeResume = modeResume,
        pendingSessionPay = park,
        pauseSafety = pauseSafety,
    )

    private fun state(vararg regions: PlatformRegion) = AppState(
        regions = Regions(platforms = regions.associateBy { it.platform }),
        // The newest observation this state has seen — `recoveryHygiene`'s `lastSeen` anchor. Set
        // to the graces' arm time so `observed == 0` and a full window is served live by default;
        // the serve-live cases below move it deliberately.
        timestamp = 10_500L,
    )

    private val destructive = PendingDestructive(
        kind = DestructiveKind.SESSION_END, since = 10_500L, deadline = 20_500L,
    )
    private val modeResume = PendingModeResume(since = 11_000L, deadline = 19_000L)
    private val park = PendingSessionPay(16.70, 12_000L, 15_000L, Flow.Idle)

    /** "Now" at the moment of the restore — far past every deadline above. */
    private val NOW = 1_000_000L

    /**
     * (type, platform, deadline) — what an enumeration test is actually about. The `wakeId` is
     * MINTED by the hygiene, so it is not a fixture value; it gets its own test below.
     */
    private fun List<PendingDeadline>.described() = map { Triple(it.type, it.platform, it.wake.deadline) }

    @Test
    fun `a state with no pendings enumerates nothing`() {
        assertTrue(state(region(Platform.DoorDash)).pendingDeadlineTimers().isEmpty())
        assertTrue("and an empty state is total, not an error", AppState().pendingDeadlineTimers().isEmpty())
    }

    @Test
    fun `a restored destructive grace enumerates its GRACE_COMMIT deadline`() {
        assertEquals(
            listOf(Triple(TimeoutType.GRACE_COMMIT, Platform.DoorDash, 20_500L)),
            state(region(Platform.DoorDash, destructive = destructive)).pendingDeadlineTimers().described(),
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
            listOf(Triple(TimeoutType.GRACE_COMMIT, Platform.DoorDash, 20_500L)),
            state(region(Platform.DoorDash, destructive = destructive, session = null))
                .pendingDeadlineTimers().described(),
        )
    }

    @Test
    fun `a region holding every pending enumerates only the two that are re-armed`() {
        val enumerated = state(
            region(
                Platform.DoorDash,
                destructive = destructive,
                modeResume = modeResume,
                park = park,
                pauseSafety = PendingWake(40_000L, 3L),
            ),
        ).pendingDeadlineTimers().described()

        assertEquals(
            listOf(
                Triple(TimeoutType.GRACE_COMMIT, Platform.DoorDash, 20_500L),
                Triple(TimeoutType.SESSION_PAUSED_SAFETY, Platform.DoorDash, 40_000L),
            ),
            enumerated,
        )
    }

    @Test
    fun `the pause-safety net is enumerated AS-IS, dead time included`() {
        // #1054 round 4. Unlike a destructive grace — which observes nothing while the process is
        // dead, so `recoveryHygiene` serves its remaining window live — this countdown belongs to
        // the PLATFORM and ran on the platform's clock throughout. A deadline already past is a
        // real fact, and firing at once (ending the dash) is the designed outcome: it is the fix
        // for a pocketed phone whose countdown ended overnight, leaving a session the next
        // morning's dash would RESUME.
        assertEquals(
            listOf(Triple(TimeoutType.SESSION_PAUSED_SAFETY, Platform.DoorDash, 1_000L)),
            state(region(Platform.DoorDash, pauseSafety = PendingWake(1_000L, 2L)))
                .pendingDeadlineTimers().described(),
        )
    }

    @Test
    fun `each platform's pendings carry that platform, never a literal`() {
        val enumerated = state(
            region(Platform.DoorDash, destructive = destructive),
            region(Platform.Uber, destructive = destructive.copy(deadline = 30_000L)),
        ).pendingDeadlineTimers().described()

        assertEquals(
            setOf(
                Triple(TimeoutType.GRACE_COMMIT, Platform.DoorDash, 20_500L),
                Triple(TimeoutType.GRACE_COMMIT, Platform.Uber, 30_000L),
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
        ).recoveryHygiene(nowMs = NOW)

        for ((platform, region) in cleaned.regions.platforms) {
            assertNull("the park is stale evidence on $platform", region.pendingSessionPay)
            assertNull("so is the resume on $platform", region.pendingModeResume)
        }
        val kept = cleaned.regions.platforms.getValue(Platform.DoorDash).pendingDestructive
        assertEquals("the decision in flight survives, and its arm time is untouched (#732)", 10_500L, kept?.since)
    }

    // =====================================================================
    // #1054 round 4 — a restored destructive grace serves its REMAINING window live
    // =====================================================================

    @Test
    fun `a grace that observed none of its window is re-based to a FULL window from now`() {
        // The state's own `timestamp` (its newest observation) equals the grace's `since`, so
        // nothing was observed before the process died. Dead time is not un-contradicted time: the
        // collapsed receipt's expansion (#1033) and the misrecognized summary's contradicting task
        // frame (#431) can both still land, and before round 4 a restored grace re-armed at the
        // 1 ms floor and committed before any live frame could arrive.
        val cleaned = state(region(Platform.DoorDash, destructive = destructive))
            .recoveryHygiene(nowMs = NOW)

        val kept = cleaned.regions.platforms.getValue(Platform.DoorDash).pendingDestructive!!
        assertEquals("the full 10 s window, re-based onto now", NOW + 10_000L, kept.deadline)
        assertEquals("`since` never moves — #732 stamps the commit at it", 10_500L, kept.since)
        assertEquals(
            "and the enumerator re-arms the re-based deadline, not the stale one",
            listOf(Triple(TimeoutType.GRACE_COMMIT, Platform.DoorDash, NOW + 10_000L)),
            cleaned.pendingDeadlineTimers().described(),
        )
    }

    @Test
    fun `a partly-observed window is re-based to only what is LEFT`() {
        // 4 s of the 10 s window elapsed with frames landing before the crash; 6 s remain.
        val cleaned = state(region(Platform.DoorDash, destructive = destructive))
            .copy(timestamp = 14_500L)
            .recoveryHygiene(nowMs = NOW)

        assertEquals(
            NOW + 6_000L,
            cleaned.regions.platforms.getValue(Platform.DoorDash).pendingDestructive?.deadline,
        )
    }

    @Test
    fun `a window already fully elapsed re-bases to now, not to the past`() {
        val cleaned = state(region(Platform.DoorDash, destructive = destructive))
            .copy(timestamp = 25_000L)
            .recoveryHygiene(nowMs = NOW)

        assertEquals(
            "clamped at zero remaining — the engine's 1 ms floor then fires it immediately",
            NOW,
            cleaned.regions.platforms.getValue(Platform.DoorDash).pendingDestructive?.deadline,
        )
    }

    @Test
    fun `re-basing is a FIXED POINT — a second restart with no observation returns the same window`() {
        // Astra's finding 1. Re-basing moves the deadline but NOT `AppState.timestamp`, so a naive
        // second pass measured `observed` against a timestamp the first pass had already accounted
        // for and handed back the whole elapsed interval as fresh window: a 2.5 s grace restored at
        // 100 000 and again at 101 000 became 193 500 ms, and a crash loop stretched it without
        // bound. `servedFrom` is the anchor that closes it.
        val short = PendingDestructive(
            kind = DestructiveKind.SESSION_END, since = 10_000L, deadline = 12_500L,
        )
        val crashed = state(region(Platform.DoorDash, destructive = short)).copy(timestamp = 10_000L)

        val first = crashed.recoveryHygiene(nowMs = 100_000L)
        val firstPend = first.regions.platforms.getValue(Platform.DoorDash).pendingDestructive!!
        assertEquals("the full 2.5 s window, re-based", 102_500L, firstPend.deadline)
        assertEquals("and the anchor is recorded", 100_000L, firstPend.servedFrom)

        // The second restart replays the CHECKPOINTED state, which still carries timestamp 10 000.
        val second = first.recoveryHygiene(nowMs = 101_000L)
        assertEquals(
            "the same 2.5 s again, not 92.5 s of dead time handed back as window",
            103_500L,
            second.regions.platforms.getValue(Platform.DoorDash).pendingDestructive?.deadline,
        )
    }

    @Test
    fun `a restart AFTER a live observation serves only what that observation left`() {
        // The other side of the fixed point: `servedFrom` must not freeze the accounting either.
        // A second crash at 101 000 with a live frame at 101 000 means 1 s of the window really
        // was served, so 1.5 s remain.
        val short = PendingDestructive(
            kind = DestructiveKind.SESSION_END, since = 10_000L, deadline = 12_500L,
        )
        val first = state(region(Platform.DoorDash, destructive = short))
            .copy(timestamp = 10_000L)
            .recoveryHygiene(nowMs = 100_000L)

        val observed = first.copy(timestamp = 101_000L)
        val second = observed.recoveryHygiene(nowMs = 101_000L)

        assertEquals(
            101_000L + 1_500L,
            second.regions.platforms.getValue(Platform.DoorDash).pendingDestructive?.deadline,
        )
    }

    @Test
    fun `a re-based grace takes a FRESH generation, and keeps its arm time`() {
        val cleaned = state(region(Platform.DoorDash, destructive = destructive))
            .recoveryHygiene(nowMs = NOW)
        val pend = cleaned.regions.platforms.getValue(Platform.DoorDash).pendingDestructive!!

        assertTrue("never the legacy 0 — a restored arm must be identifiable", pend.wakeId > 0L)
        assertEquals("`since` never moves — #732 stamps the commit at it", 10_500L, pend.since)
        assertEquals(
            "and the enumerator carries that generation to the arm",
            pend.wakeId,
            cleaned.pendingDeadlineTimers().single { it.type == TimeoutType.GRACE_COMMIT }.wake.wakeId,
        )
    }

    @Test
    fun `the pause-safety deadline is left exactly as it was`() {
        val cleaned = state(region(Platform.DoorDash, pauseSafety = PendingWake(1_000L, 2L)))
            .recoveryHygiene(nowMs = NOW)

        assertEquals(
            1_000L,
            cleaned.regions.platforms.getValue(Platform.DoorDash).pauseSafety?.deadline,
        )
    }
}
