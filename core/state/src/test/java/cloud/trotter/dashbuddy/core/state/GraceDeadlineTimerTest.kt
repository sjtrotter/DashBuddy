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
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
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
 *
 * **Round 2 narrows the inclusive half to a TIMER's own fire** (`PlatformRegionStepper.deadlineLapsed`).
 * Both graces have a cancel arm competing with their own commit, and the expiry runs at the TOP of
 * the step — so under a plain `>=` a contradicting frame stamped exactly on the deadline committed
 * the thing it arrived to contradict. On the resume that was worse than a lost cancel: the commit
 * MINTS a session, and the frame's own Online→Paused transition then left `diffMode` with no edge to
 * report, producing a dash no `DASH_START` describes. Round 2 also has `endSession` clear the graced
 * resume, since a resume surviving its session's end mints a phantom dash the same way.
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
    fun `an ordinary frame stamped ON the deadline does NOT commit - equality belongs to the timer`() {
        // #1054 round 2: the inclusive case exists for a TIMER's own fire, which is stamped at the
        // deadline it was armed for. An ordinary frame keeps the strict semantics, because the
        // expiry runs at the top of the step — ahead of the frame's own transition — so a frame
        // that arrived to CONTRADICT the pending would otherwise commit the very thing it
        // contradicts. See `a contradicting frame ON the resume deadline reaches the cancel arm`
        // for the shape that made this matter.
        val onDeadline = offlineFrame(endDeadline)

        val after = machine.step(state(region(destructive = sessionEnd())), onDeadline).newState.dd()
        assertNotNull("equality alone is not a lapse for a frame", after.session)
        assertNotNull("the grace stands, waiting for its timer or a later frame", after.pendingDestructive)
    }

    @Test
    fun `an ordinary frame stamped one millisecond LATER commits exactly as it always did`() {
        val afterDeadline = offlineFrame(endDeadline + 1L)

        val ended = machine.step(state(region(destructive = sessionEnd())), afterDeadline).newState.dd()
        assertNull("a strictly-later frame commits the grace, unchanged by round 2", ended.session)
    }

    private fun offlineFrame(at: Long) = Observation.Screen(
        timestamp = at,
        captureId = null,
        ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Offline,
        parsed = ParsedFields.IdleFields(),
    )

    // =====================================================================
    // #1054 round 2 — a contradicting frame at equality reaches its cancel arm
    // =====================================================================

    @Test
    fun `a contradicting frame ON the resume deadline reaches the cancel arm, minting no session`() {
        // Astra's sequence, driven end-to-end from a FRESH state so nothing is hand-assembled: a
        // paused screen, an online idle frame that arms the resume grace, then a paused screen
        // stamped exactly on the deadline. Under a plain `>=` the expiry ran first and committed
        // Paused→Online — which MINTS a session, `applyModeTransition` doing so whenever
        // `session == null` — and the frame's own Online→Paused transition then left `diffMode`
        // seeing Paused→Paused, so no `DASH_START` was ever emitted for it. A session no event
        // describes.
        var s = AppState()
        s = machine.step(s, pausedFrame(1_000L)).newState
        s = machine.step(s, onlineIdleFrame(2_000L)).newState

        val pend = s.dd().pendingModeResume
        assertNotNull("the online frame armed the resume grace", pend)
        assertEquals("armed for the 8 s resume window", 10_000L, pend!!.deadline)

        val transition = machine.step(s, pausedFrame(pend.deadline))
        val dd = transition.newState.dd()

        assertNull("no phantom dash was minted", dd.session)
        assertNull("the paused frame CANCELLED the resume, as it does one millisecond earlier", dd.pendingModeResume)
        assertTrue(
            "and nothing claims a dash started",
            transition.effects.filterIsInstance<AppEffect.LogEvent>()
                .none { it.event.type == AppEventType.DASH_START },
        )
    }

    @Test
    fun `a contradicting task frame ON a SESSION_END deadline cancels the misrecognized end`() {
        // The destructive analogue: an authoritative `SESSION_END` (a dash summary) that was a
        // misrecognition, contradicted by a task-flow frame landing exactly on the deadline. The
        // #431 cancel arm must win the tie — committing the end first would tear down a live dash
        // on the strength of a frame that proves it is still running.
        val summaryAt = 2_000L
        val deadline = summaryAt + TransitionPolicy.AUTHORITATIVE_GRACE_MS

        var s = AppState()
        s = machine.step(s, onlineIdleFrame(1_000L)).newState
        s = machine.step(
            s,
            Observation.Screen(
                timestamp = summaryAt,
                captureId = null,
                ruleId = "doordash.screen.dash_summary",
                metadata = ReplayMetadata.EMPTY,
                flow = Flow.SessionEnded,
                modeHint = Mode.Offline,
                parsed = ParsedFields.SessionEndedFields(totalEarnings = 25.0),
            ),
        ).newState
        val sessionId = s.dd().session?.sessionId
        assertNotNull("a live session is what the summary is about to end", sessionId)
        assertEquals("the authoritative grace is armed", deadline, s.dd().pendingDestructive?.deadline)

        val contradicted = machine.step(s, taskFrame(deadline)).newState.dd()
        assertNull("the misrecognition is cancelled at equality", contradicted.pendingDestructive)
        assertEquals("the same dash is still running", sessionId, contradicted.session?.sessionId)

        // ...while the timer's own fire at that same instant commits, which is the whole point of
        // the asymmetry.
        val committed = machine.step(s, wake(TimeoutType.GRACE_COMMIT, deadline)).newState.dd()
        assertNull("a GRACE_COMMIT at the deadline still ends the dash", committed.session)
    }

    private fun pausedFrame(at: Long) = Observation.Screen(
        timestamp = at,
        captureId = null,
        ruleId = "doordash.screen.dash_paused",
        metadata = ReplayMetadata.EMPTY,
        flow = null,
        modeHint = Mode.Paused,
        parsed = ParsedFields.PausedFields(remainingText = "5:00", remainingMillis = 300_000L),
    )

    private fun onlineIdleFrame(at: Long) = Observation.Screen(
        timestamp = at,
        captureId = null,
        ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle,
        modeHint = Mode.Online,
        parsed = ParsedFields.IdleFields(),
    )

    private fun taskFrame(at: Long) = Observation.Screen(
        timestamp = at,
        captureId = null,
        ruleId = "doordash.screen.pickup_navigation",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.TaskPickupNavigation,
        modeHint = null,
        parsed = ParsedFields.TaskFields(
            phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = "Chipotle",
        ),
    )

    // =====================================================================
    // #1054 round 3 — equality belongs to the timer's OWN (type, platform)
    // =====================================================================

    @Test
    fun `a SESSION_PAUSED_SAFETY fire on a resume deadline does not commit the resume`() {
        // Round 2 granted equality to ANY `Observation.Timeout`, which looked harmless until you
        // notice the deadlines coincide SYSTEMATICALLY: `PAUSE_RESUME_GRACE_MS` and
        // `RECEIPT_EXPAND_GRACE_MS` are both 8 000 ms and every timer in a region shares one clock.
        // The safety fire would then commit Paused→Online at the top of `stepCore` — and
        // `handleTimeout`'s own `prev.mode == Paused` guard, evaluated after, is now false, so the
        // safety net's Paused→Offline + SESSION_END grace is silently dropped and the dash stays
        // Online with nothing left to end it.
        val region = region(
            mode = Mode.Paused,
            modeResume = PendingModeResume(since = resumeArmedAt, deadline = resumeDeadline),
        )

        val after = machine.step(state(region), wake(TimeoutType.SESSION_PAUSED_SAFETY, resumeDeadline))
            .newState.dd()

        assertEquals("the safety transition ran: Paused → Offline", Mode.Offline, after.mode)
        assertEquals(
            "with its own graced end armed, which is the whole point of the net",
            DestructiveKind.SESSION_END,
            after.pendingDestructive?.kind,
        )
        assertNull(
            "the resume is resolved by that Paused→Offline commit (#605), not committed by it — " +
                "the failure mode being guarded is Mode.Online, which the mode assertion above pins",
            after.pendingModeResume,
        )
        assertNotNull("and the session survives into its own grace", after.session)
    }

    @Test
    fun `the resume's OWN fire at that same instant still commits`() {
        // The control for the case above: nothing about the narrowing weakens the fix it narrows.
        val region = region(
            mode = Mode.Paused,
            modeResume = PendingModeResume(since = resumeArmedAt, deadline = resumeDeadline),
        )

        val after = machine.step(state(region), wake(TimeoutType.MODE_RESUME_COMMIT, resumeDeadline))
            .newState.dd()

        assertEquals(Mode.Online, after.mode)
        assertNull(after.pendingModeResume)
    }

    @Test
    fun `another platform's GRACE_COMMIT fire never lapses this region's grace at equality`() {
        // The platform half of the same test. `stepPlatforms` steps only `obs.platform`'s region,
        // so this is belt-and-braces — but the predicate states (type, platform) because a timer's
        // fire is evidence about the pending it was armed for and about nothing else.
        val uberWake = Observation.Timeout(
            timestamp = endDeadline,
            type = TimeoutType.GRACE_COMMIT,
            targetPlatform = Platform.Uber,
        )

        val after = machine.step(state(region(destructive = sessionEnd())), uberWake).newState.dd()
        assertNotNull("DoorDash's dash is untouched by Uber's timer", after.session)
        assertNotNull("and its grace still stands", after.pendingDestructive)
    }

    // =====================================================================
    // #1054 round 2 — a terminal end invalidates that session's graced resume
    // =====================================================================

    @Test
    fun `a committing SESSION_END clears the standing resume and cancels its timer`() {
        // The phantom-dash shape: pause sheet → online idle (resume armed) → the summary's grace
        // commits the end. `endSession` used to leave `pendingModeResume` standing on a now
        // session-less region, and `commitModeResume` MINTS a session when there is none — so the
        // resume's own timer (or the #1054 recovery re-arm) started a dash out of a stale intent.
        val region = region(
            mode = Mode.Paused,
            destructive = sessionEnd(),
            modeResume = PendingModeResume(since = resumeArmedAt, deadline = resumeDeadline),
        )

        val transition = machine.step(state(region), wake(TimeoutType.GRACE_COMMIT, endDeadline))
        val dd = transition.newState.dd()

        assertNull("the dash ended", dd.session)
        assertNull("and its graced resume went with it", dd.pendingModeResume)
        assertTrue(
            "the resume's wake timer is cancelled, not left to fire into a dead session",
            transition.effects.filterIsInstance<AppEffect.CancelTimeout>()
                .any { it.type == TimeoutType.MODE_RESUME_COMMIT && it.platform == platform },
        )

        // And the stale fire that used to mint the phantom now finds nothing to commit.
        val stale = machine.step(
            AppState(
                regions = Regions(
                    flow = FlowRegion(flow = Flow.Idle, activePlatform = platform),
                    platforms = mapOf(platform to dd),
                ),
            ),
            wake(TimeoutType.MODE_RESUME_COMMIT, resumeDeadline + 1_000L),
        )
        assertNull("no session is minted out of thin air", stale.newState.dd().session)
        assertTrue(
            "and nothing claims a dash started",
            stale.effects.filterIsInstance<AppEffect.LogEvent>()
                .none { it.event.type == AppEventType.DASH_START },
        )
    }
}
