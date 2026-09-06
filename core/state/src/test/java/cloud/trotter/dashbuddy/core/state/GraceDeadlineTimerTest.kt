package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingModeResume
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1054 part 1 — the two older graces land on an EXACT-deadline wake, and re-arm on an early one.
 *
 * Both wake timers are armed for exactly `deadline - obs.timestamp`, but the fired observation is
 * stamped with the wall clock. So the ordinary case is a fire landing precisely ON the deadline,
 * and a clock step-back (or a coarse scheduler) puts it BEFORE. Under the old strict `>` expiry the
 * first was a no-op and under the old no-re-arm diff the second was too — in both cases the pending
 * sat there with nothing left to wake it. That was survivable only while you believed an ordinary
 * frame would come along and re-drive the lazy expiry; the very case `GRACE_COMMIT` was built for
 * (#431 — offline with the app backgrounded) is the case where no frame comes, and an unchanged
 * screen is FrameGate-deduplicated besides.
 *
 * The settle park already had both halves (#1029 rule (e) / S5). This is the other two catching up,
 * which is also why `diffDeadlineTimer` no longer has a `rearmOnEarlyWake` knob to get wrong.
 */
class GraceDeadlineTimerTest {

    private val machine = StateMachine(
        flowStepper = FlowRegionStepper(),
        platformStepper = PlatformRegionStepper(),
        crossPlatformStepper = CrossPlatformRegionStepper(),
        transitionPolicy = TransitionPolicy(),
        effectMap = EffectMap(),
    )

    private val platform = Platform.DoorDash

    // The issue's own reproduction: offline at 10 500 with the 10 s non-authoritative grace.
    private val offlineAt = 10_500L
    private val endDeadline = 20_500L

    // ...and its resume half: an online-implying frame at 11 000 with the 8 s resume grace.
    private val resumeArmedAt = 11_000L
    private val resumeDeadline = 19_000L

    private fun session() = Session("sess-1", startedAt = 100L)

    private fun region(
        mode: Mode = Mode.Offline,
        destructive: PendingDestructive? = null,
        modeResume: PendingModeResume? = null,
        job: Job? = null,
        task: Task? = null,
    ) = PlatformRegion(
        platform = platform,
        mode = mode,
        session = session(),
        activeJob = job,
        activeTask = task,
        pendingDestructive = destructive,
        pendingModeResume = modeResume,
        lastActedFlow = Flow.Idle,
    )

    private fun state(region: PlatformRegion, flow: Flow = Flow.Idle) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow, activePlatform = platform),
            platforms = mapOf(platform to region),
        ),
    )

    private fun wake(type: TimeoutType, at: Long) =
        Observation.Timeout(timestamp = at, type = type, targetPlatform = platform)

    private fun AppState.dd() = regions.platforms.getValue(platform)

    private fun List<AppEffect>.scheduled(type: TimeoutType) =
        filterIsInstance<AppEffect.ScheduleTimeout>().filter { it.type == type }

    private fun sessionEnd(deadline: Long = endDeadline) = PendingDestructive(
        kind = DestructiveKind.SESSION_END,
        since = offlineAt,
        deadline = deadline,
        armedFromFlow = Flow.Idle,
    )

    // =====================================================================
    // GRACE_COMMIT — the exact-deadline landing
    // =====================================================================

    @Test
    fun `a SESSION_END grace commits on a GRACE_COMMIT fire landing exactly on the deadline`() {
        val transition = machine.step(state(region(destructive = sessionEnd())), wake(TimeoutType.GRACE_COMMIT, endDeadline))
        val dd = transition.newState.dd()

        assertNull("the dash really ended — the timer's own fire is the commit", dd.session)
        assertNull("and the grace is consumed", dd.pendingDestructive)
        assertTrue(
            "the end is logged, not merely believed",
            transition.effects.filterIsInstance<AppEffect.LogEvent>()
                .any { it.event.type == AppEventType.DASH_STOP },
        )
        assertTrue(
            "a consumed grace schedules nothing — there is nothing left to wake",
            transition.effects.scheduled(TimeoutType.GRACE_COMMIT).isEmpty(),
        )
    }

    @Test
    fun `a TASK_RETIRE grace commits on a GRACE_COMMIT fire landing exactly on the deadline`() {
        val retireDeadline = 5_000L
        val region = region(
            mode = Mode.Online,
            destructive = PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE,
                since = 4_000L,
                deadline = retireDeadline,
                armedFromFlow = Flow.Idle,
            ),
            job = Job("job-1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
            task = Task(
                taskId = "task-1", jobId = "job-1", phase = TaskPhase.PICKUP,
                storeName = "H-E-B", startedAt = 300L,
            ),
        )

        val transition = machine.step(state(region), wake(TimeoutType.GRACE_COMMIT, retireDeadline))
        val dd = transition.newState.dd()

        assertNull("the overdue retire committed on its own timer's fire", dd.activeTask)
        assertNull("and the grace is consumed", dd.pendingDestructive)
        assertEquals("the task is retired, not lost", "task-1", dd.recentTasks.lastOrNull()?.taskId)
    }

    // =====================================================================
    // GRACE_COMMIT — the early fire re-arms rather than stranding
    // =====================================================================

    @Test
    fun `a GRACE_COMMIT fire one millisecond early re-arms for the remainder and commits nothing`() {
        val transition = machine.step(
            state(region(destructive = sessionEnd())),
            wake(TimeoutType.GRACE_COMMIT, endDeadline - 1L),
        )
        val dd = transition.newState.dd()

        assertNotNull("nothing is committed early — the deadline has not been reached", dd.session)
        assertNotNull("the grace stands", dd.pendingDestructive)
        val rearm = transition.effects.scheduled(TimeoutType.GRACE_COMMIT).single()
        assertEquals("re-armed for exactly the remaining millisecond", 1L, rearm.durationMs)
        assertEquals("and for THIS region", platform, rearm.platform)
    }

    @Test
    fun `a GRACE_COMMIT fire well before the deadline re-arms for the whole remainder`() {
        val transition = machine.step(
            state(region(destructive = sessionEnd())),
            wake(TimeoutType.GRACE_COMMIT, 19_000L),
        )

        assertNotNull("still pending", transition.newState.dd().pendingDestructive)
        assertEquals(
            "re-armed for the remainder, never for a fresh full window",
            1_500L,
            transition.effects.scheduled(TimeoutType.GRACE_COMMIT).single().durationMs,
        )
    }

    @Test
    fun `a stale GRACE_COMMIT fire for a REPLACED destructive re-arms and does NOT commit`() {
        // The grace was re-armed later (a fresh destructive signal moved the deadline out) while
        // the OLD timer was already in flight. Its fire is now early against the new deadline —
        // and re-arming, never committing, is the whole reason the early-wake branch schedules
        // instead of expiring: committing here would commit a decision the machine has already
        // superseded.
        val replaced = sessionEnd(deadline = 30_000L)

        val transition = machine.step(state(region(destructive = replaced)), wake(TimeoutType.GRACE_COMMIT, endDeadline))
        val dd = transition.newState.dd()

        assertNotNull("the superseded fire commits nothing", dd.session)
        assertEquals("the live deadline is untouched", replaced, dd.pendingDestructive)
        assertEquals(
            "and the timer is re-armed against the NEW deadline",
            9_500L,
            transition.effects.scheduled(TimeoutType.GRACE_COMMIT).single().durationMs,
        )
    }

    // =====================================================================
    // MODE_RESUME_COMMIT — the same two properties
    // =====================================================================

    @Test
    fun `a graced resume commits on a MODE_RESUME_COMMIT fire landing exactly on the deadline`() {
        val region = region(
            mode = Mode.Paused,
            modeResume = PendingModeResume(since = resumeArmedAt, deadline = resumeDeadline),
        )

        val transition = machine.step(state(region), wake(TimeoutType.MODE_RESUME_COMMIT, resumeDeadline))
        val dd = transition.newState.dd()

        assertEquals("the dash is back Online", Mode.Online, dd.mode)
        assertNull("the resume grace is consumed", dd.pendingModeResume)
        assertEquals("the same session resumed, not a new one", "sess-1", dd.session?.sessionId)
        assertTrue(
            "nothing is left to wake",
            transition.effects.scheduled(TimeoutType.MODE_RESUME_COMMIT).isEmpty(),
        )
    }

    @Test
    fun `a MODE_RESUME_COMMIT fire one millisecond early re-arms and stays Paused`() {
        val region = region(
            mode = Mode.Paused,
            modeResume = PendingModeResume(since = resumeArmedAt, deadline = resumeDeadline),
        )

        val transition = machine.step(state(region), wake(TimeoutType.MODE_RESUME_COMMIT, resumeDeadline - 1L))
        val dd = transition.newState.dd()

        assertEquals("still Paused — the window is not up", Mode.Paused, dd.mode)
        assertNotNull("the resume grace stands", dd.pendingModeResume)
        assertEquals(
            "re-armed for exactly the remaining millisecond",
            1L,
            transition.effects.scheduled(TimeoutType.MODE_RESUME_COMMIT).single().durationMs,
        )
    }

    // =====================================================================
    // The frames the graces were built around still behave
    // =====================================================================

    @Test
    fun `an ordinary frame at the deadline commits too - the expiry is at-or-past for every observation`() {
        // The `>=` is a property of the lazy expiry, not of the timer: an ordinary screen landing
        // exactly on the deadline is the same evidence the timer would have carried.
        val frame = Observation.Screen(
            timestamp = endDeadline,
            captureId = null,
            ruleId = "doordash.screen.waiting_for_offer",
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.Idle,
            modeHint = Mode.Offline,
            parsed = ParsedFields.IdleFields(),
        )

        val ended = machine.step(state(region(destructive = sessionEnd())), frame).newState.dd()
        assertNull("the dash ended on the frame that reached the deadline", ended.session)
    }
}
