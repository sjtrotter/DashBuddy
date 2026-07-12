package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
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
 * #732 characterization test — **pins the DOCUMENTED `sequenceId`/`occurredAt` ordering
 * invariant** (see `AppEventEntity`'s class KDoc, `core/database/.../event/AppEventEntity.kt`):
 * a graced destructive commit (`PlatformRegion.pendingDestructive` / the `GRACE_COMMIT` timer,
 * #431) stamps its eventually-logged event's timestamp at grace-**ARM** time (`pend.since`),
 * not commit/append time — so the event's `occurredAt` can be EARLIER than an intervening,
 * non-graced event's `occurredAt`, even though the graced event's row is not appended to (and
 * does not receive its `sequenceId` in) the `app_events` log until later — after the
 * intervening event.
 *
 * The scenario below drives that through the REAL [PlatformRegionStepper] + [EffectMap]
 * machinery (no hand-waved shortcuts): a `SESSION_END` grace is armed while a pickup task is
 * active, an intervening non-flow observation is processed WHILE the grace is still open
 * (proving real processing has moved past the arm time before the grace ever commits), and
 * then a `GRACE_COMMIT` timeout past the deadline lazily commits the session end. The
 * resulting `PICKUP_CONFIRMED` — minted by the #596/#526 close-out sweep off the
 * grace-stamped `Task.completedAt` — carries `occurredAt == pend.since`, which is EARLIER than
 * the intervening observation's own timestamp.
 *
 * This is a **documented, ACCEPTED tradeoff** (#732 Option B — the dev decided NOT to
 * re-stamp `occurredAt` to append time, because `pend.since` is the honest domain moment the
 * destructive signal appeared). If this test starts failing because a future change makes the
 * graced commit re-stamp to "now" instead of `pend.since`, that is a deliberate behavior
 * change requiring its own decision — re-read #732 before touching this test to match.
 */
class GracedCommitOrderingInvariantTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()
    private val effectMap = EffectMap()
    private val platform = Platform.DoorDash
    private val session = Session("sess-1", startedAt = 100L)

    // The destructive signal (e.g. the offline/idle screen that implies "the dash really
    // ended") appeared at armedAt — this is `pend.since`.
    private val armedAt = 2_000L

    // The grace window's deadline — nothing commits before this.
    private val deadline = 2_500L

    // A real, unrelated frame the state machine processes WHILE the grace is still open —
    // later than armedAt, but well before the eventual commit.
    private val interveningAt = 2_200L

    // The GRACE_COMMIT timer's own fire time — past the deadline, this is when the lazy
    // expiry actually commits (and the resulting event is finally appended to the log).
    private val commitAt = 3_000L

    private val pickupTaskFlow = FlowRegion(flow = Flow.TaskPickupArrived, activePlatform = platform)

    private fun pickupTask(completedAt: Long? = null) = Task(
        taskId = "pk", jobId = "job-1", phase = TaskPhase.PICKUP,
        storeName = "H-E-B", arrivedAt = 400L, startedAt = 300L, completedAt = completedAt,
    )

    private fun job() = Job(
        jobId = "job-1", offerStoreHint = listOf("H-E-B"), parentOfferHash = null,
        startedAt = 200L, tasks = emptyList(),
    )

    private fun armedRegion() = PlatformRegion(
        platform = platform, mode = Mode.Online, session = session,
        activeJob = job(), activeTask = pickupTask(),
        pendingDestructive = PendingDestructive(DestructiveKind.SESSION_END, since = armedAt, deadline = deadline),
        lastActedFlow = Flow.TaskPickupArrived,
    )

    private fun uiInput(t: Long) = Observation.UiInput(timestamp = t, action = "noop", targetPlatform = platform)

    private fun graceCommitTimeout(t: Long) =
        Observation.Timeout(timestamp = t, type = TimeoutType.GRACE_COMMIT, targetPlatform = platform)

    private fun state(region: PlatformRegion, flow: Flow) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow, activePlatform = platform),
            platforms = mapOf(platform to region),
        ),
    )

    @Test
    fun `an intervening observation while the grace is open does not commit it`() {
        val armed = armedRegion()
        val afterIntervening =
            stepper.step(armed, pickupTaskFlow, pickupTaskFlow, uiInput(interveningAt), policy)

        assertNotNull("the SESSION_END grace is still pending — deadline not reached", afterIntervening.pendingDestructive)
        assertEquals("session untouched by the non-committing intervening frame", "sess-1", afterIntervening.session?.sessionId)
        assertEquals("active task untouched", "pk", afterIntervening.activeTask?.taskId)
    }

    @Test
    fun `the lazy-expiry commit stamps completedAt at pend-since, not at commit time`() {
        val armed = armedRegion()
        val afterIntervening =
            stepper.step(armed, pickupTaskFlow, pickupTaskFlow, uiInput(interveningAt), policy)
        val afterCommit =
            stepper.step(afterIntervening, pickupTaskFlow, pickupTaskFlow, graceCommitTimeout(commitAt), policy)

        assertNull("SESSION_END committed — session cleared", afterCommit.session)
        assertNull("activeJob cleared by the session end", afterCommit.activeJob)
        assertNull("grace cleared on commit", afterCommit.pendingDestructive)
        val retiredTask = afterCommit.recentTasks.single { it.taskId == "pk" }
        assertEquals(
            "the retired task's completedAt is stamped at pend.since (armedAt), not obs.timestamp (commitAt)",
            armedAt,
            retiredTask.completedAt,
        )
        assertTrue(
            "the INVERSION: the committed domain timestamp (armedAt) is EARLIER than the " +
                "intervening frame's own timestamp, even though the intervening frame was " +
                "processed — and would append to app_events — BEFORE this commit did",
            retiredTask.completedAt!! < interveningAt,
        )
    }

    @Test
    fun `the resulting PICKUP_CONFIRMED event carries occurredAt equal to pend-since (armedAt), earlier than the intervening frame`() {
        val armed = armedRegion()
        val afterIntervening =
            stepper.step(armed, pickupTaskFlow, pickupTaskFlow, uiInput(interveningAt), policy)
        val commitObs = graceCommitTimeout(commitAt)
        val afterCommit =
            stepper.step(afterIntervening, pickupTaskFlow, pickupTaskFlow, commitObs, policy)

        // Diff the SAME prev/next pair the real StateManagerV2 would diff around this exact
        // commit step, with the SAME observation that drove it — this is the #596/#526
        // close-out sweep: `endSession` unconditionally clears `activeJob`, so a job that was
        // never a "physically complete" dropoff job (there ARE no dropoffs here) still gets
        // its arrived pickup swept into a PICKUP_CONFIRMED.
        val effects = effectMap.diff(
            state(afterIntervening, Flow.TaskPickupArrived),
            state(afterCommit, Flow.Idle),
            commitObs,
        )

        val confirmed = effects.filterIsInstance<AppEffect.LogEvent>()
            .single { it.event.type == AppEventType.PICKUP_CONFIRMED }

        assertEquals(
            "PICKUP_CONFIRMED's occurredAt is the ARMED time (pend.since), not the commit " +
                "observation's timestamp — this is the value that lands in AppEventEntity.occurredAt " +
                "(#732, see AppEventEntity's class KDoc)",
            armedAt,
            confirmed.event.occurredAt,
        )
        assertTrue(
            "the pinned inversion: this event's occurredAt is EARLIER than an intervening " +
                "frame's timestamp, despite this event not appending to the log until the " +
                "GRACE_COMMIT fires at commitAt (well after the intervening frame was processed)",
            confirmed.event.occurredAt < interveningAt,
        )
        assertTrue("commitAt is indeed after the intervening frame in real/processing time", commitAt > interveningAt)
    }
}
