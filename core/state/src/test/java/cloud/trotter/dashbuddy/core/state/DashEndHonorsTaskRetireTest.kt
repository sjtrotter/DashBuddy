package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.JobAcceptMismatchPayload
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.CrossPlatformRegion
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1078 — **a dash end HONORS a pending task retire instead of discarding it.**
 *
 * The fielded defect (2026-09-08, build 8028691a, db seq 2007/2008): the dropoff screen gave way to
 * the idle map, arming a `TASK_RETIRE`; 8.4 s later the dash-summary screen armed a `SESSION_END`
 * over it. The destructive slot holds ONE pending, so the retire — and with it the evidence that the
 * delivery really finished — was thrown away 1.6 s before its own deadline. The eventual `endSession`
 * force-stamped `completedAt`, and the amdt-#5 T3 guard ([mintQualified]) correctly refused to mint a
 * row for an unqualified force-stamp. Net: $21.00 of delivered work, no `DELIVERY_COMPLETED`, no row,
 * and not one WARN — only a `DASH_STOP` and a `DELIVERY_CONFIRMED` at the same instant.
 *
 * The fix is absorption, not replacement: the `SESSION_END` carries the retire's `since` forward as
 * [PendingDestructive.absorbedRetireSince], and `endSession` retires the task at THAT instant exactly
 * as the retire's own expiry would have. The T3 guard is untouched — an end with nothing absorbed
 * still force-stamps and still mints nothing (test 3), which is what keeps a blown-through pickup or
 * an un-arrived dropoff out of the money.
 *
 * Rule 2 covers the shape with no idle frame at all: the platform does not let a dasher end a dash
 * with an active order, so an ARRIVED dropoff still active at the AUTHORITATIVE summary is a
 * delivery the machine simply has not retired yet (the 09-05 sighting on #1078).
 *
 * #1095 rides along: the #810 tripwire now sees the teardown close, attributed to the job's own
 * session, reading mint-qualified evidence, with the single-accept floor lifted on a session end.
 */
class DashEndHonorsTaskRetireTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()
    private val effectMap = EffectMap()
    private val platform = Platform.DoorDash

    // The 09-08 timings, rebased on a round number. Retire grace 10 s, authoritative grace 2.5 s.
    private val arrivedAt = 100_000L
    private val idleAt = 200_000L
    private val summaryAt = idleAt + 8_400L
    private val retireDeadline = idleAt + 10_000L
    private val summaryDeadline = summaryAt + 2_500L

    private val session = Session("dash-A", startedAt = 10_000L, runningEarnings = 21.0)

    private fun drop(arrived: Long? = arrivedAt) = Task(
        taskId = "d1",
        jobId = "J1",
        phase = TaskPhase.DROPOFF,
        subPhase = if (arrived != null) TaskSubFlow.ARRIVED else TaskSubFlow.NAVIGATION,
        storeName = "Test Merchant",
        customerNameHash = "cust-1",
        startedAt = 90_000L,
        arrivedAt = arrived,
    )

    /** One accepted, pay-bearing offer — the #1078 shape, and #1095's single-accept floor case. */
    private fun job(task: Task) = Job(
        jobId = "J1",
        offerStoreHint = listOf("Test Merchant"),
        parentOfferHash = "offer-h",
        acceptedOffers = listOf(
            AcceptedOfferEconomics(offerHash = "offer-h", payAmount = 21.0, acceptedAt = 80_000L),
        ),
        tasks = listOf(task),
        startedAt = 80_000L,
    )

    private fun onDropoff(task: Task = drop()) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = session,
        activeJob = job(task),
        activeTask = task,
        lastActedFlow = Flow.TaskDropoffArrived,
    )

    private fun flowRegion(flow: Flow) = FlowRegion(flow = flow, activePlatform = platform)

    private fun state(region: PlatformRegion, flow: Flow) = AppState(
        regions = Regions(
            flow = flowRegion(flow),
            platforms = mapOf(platform to region),
            crossPlatform = CrossPlatformRegion(),
        ),
    )

    private fun screen(
        flow: Flow,
        at: Long,
        parsed: ParsedFields = ParsedFields.None,
        modeHint: Mode? = null,
    ) = Observation.Screen(
        timestamp = at,
        captureId = "cap-$at",
        ruleId = "doordash.screen.$flow",
        metadata = ReplayMetadata.EMPTY,
        flow = flow,
        modeHint = modeHint,
        parsed = parsed,
    )

    private fun dropoffFrame(at: Long) = screen(
        Flow.TaskDropoffArrived, at,
        parsed = ParsedFields.TaskFields(
            storeName = "Test Merchant",
            phase = TaskPhase.DROPOFF,
            subFlow = TaskSubFlow.ARRIVED,
            customerNameHash = "cust-1",
        ),
    )

    private fun summaryFrame(at: Long, modeHint: Mode? = null) = screen(
        Flow.SessionEnded, at,
        parsed = ParsedFields.SessionEndedFields(totalEarnings = 21.0),
        modeHint = modeHint,
    )

    /** This pending's OWN wake — identity is the generation, never the deadline (#1054). */
    private fun graceWake(pend: PendingDestructive, at: Long = pend.deadline) = Observation.Timeout(
        timestamp = at,
        type = TimeoutType.GRACE_COMMIT,
        targetPlatform = platform,
        payload = ObservationPayload.GraceWake(pend.wakeId),
    )

    private fun step(region: PlatformRegion, prevFlow: Flow, obs: Observation): PlatformRegion =
        stepper.step(region, flowRegion(prevFlow), flowRegion(prevFlow), obs, policy)

    private fun completions(prev: PlatformRegion, next: PlatformRegion, obs: Observation) =
        effectMap.diff(state(prev, Flow.SessionEnded), state(next, Flow.Idle), obs)
            .filterIsInstance<AppEffect.LogEvent>()

    private fun deliveries(effects: List<AppEffect.LogEvent>): List<DeliveryPayload> =
        effects.filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
            .map { it.event.payload as DeliveryPayload }

    private fun mismatches(effects: List<AppEffect.LogEvent>): List<AppEffect.LogEvent> =
        effects.filter { it.event.type == AppEventType.JOB_ACCEPT_MISMATCH }

    // =========================================================================
    // 1 — the 09-08 shape, end to end
    // =========================================================================

    @Test
    fun `the summary ABSORBS a live task retire and the commit mints one completion`() {
        val onDrop = onDropoff()

        // The dropoff screen gives way to the idle map — a TASK_RETIRE is armed.
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val retire = afterIdle.pendingDestructive
        assertNotNull("the idle frame arms a retire", retire)
        assertEquals(DestructiveKind.TASK_RETIRE, retire!!.kind)
        assertEquals(idleAt, retire.since)
        assertEquals(retireDeadline, retire.deadline)
        assertNull("a TASK_RETIRE never carries an absorbed value", retire.absorbedRetireSince)

        // 8.4 s later — 1.6 s before the retire's own deadline — the dash summary arms the end.
        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(summaryAt))
        val end = afterSummary.pendingDestructive
        assertNotNull("the summary arms a session end", end)
        assertEquals(DestructiveKind.SESSION_END, end!!.kind)
        assertTrue("the summary end is authoritative", end.authoritative)
        assertEquals(
            "the retire's `since` is ABSORBED, not discarded (#1078)",
            idleAt, end.absorbedRetireSince,
        )
        assertEquals(summaryAt, end.since)
        assertEquals(summaryDeadline, end.deadline)
        assertNotEquals("a replacement pending mints a fresh wake generation", retire.wakeId, end.wakeId)
        assertEquals("the task is still active while the end is graced", "d1", afterSummary.activeTask?.taskId)

        // The GRACE_COMMIT wake armed for THIS pending.
        val wake = graceWake(end)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)

        assertNull("the session ended", afterCommit.session)
        assertNull("the job closed with it", afterCommit.activeJob)
        assertNull("the task left the active slot", afterCommit.activeTask)
        val retired = afterCommit.recentTasks.single { it.taskId == "d1" }
        assertEquals(
            "the drop is retired at the ABSORBED retire instant — the honest completion time, " +
                "not the summary's and not the commit's",
            idleAt, retired.completedAt,
        )

        val effects = completions(afterSummary, afterCommit, wake)
        val rows = deliveries(effects)
        assertEquals("exactly one DELIVERY_COMPLETED — the row that was lost in the field", 1, rows.size)
        assertEquals("d1", rows.single().taskId)
        assertNotNull(
            "the receipt-less completion is priced by the #691 offer-pay estimate",
            rows.single().offerPayShare,
        )
        assertEquals(21.0, rows.single().offerPayShare!!, 0.0001)
        assertEquals(
            "exactly one DASH_STOP",
            1, effects.count { it.event.type == AppEventType.DASH_STOP },
        )
        assertEquals(
            "an honored, accounted drop strands nothing — the #810 tripwire stays quiet",
            0, mismatches(effects).size,
        )
    }

    // =========================================================================
    // 2 — rule 2: no idle frame at all
    // =========================================================================

    @Test
    fun `an ARRIVED dropoff active at the authoritative summary is absorbed on its own`() {
        val onDrop = onDropoff()
        val afterSummary = step(onDrop, Flow.TaskDropoffArrived, summaryFrame(summaryAt))
        val end = afterSummary.pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertEquals(
            "with no retire to absorb, the summary's OWN timestamp is the retire instant",
            summaryAt, end.absorbedRetireSince,
        )

        val wake = graceWake(end)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)
        assertEquals(summaryAt, afterCommit.recentTasks.single { it.taskId == "d1" }.completedAt)
        val effects = completions(afterSummary, afterCommit, wake)
        assertEquals("the delivery is recorded", 1, deliveries(effects).size)
        assertEquals(0, mismatches(effects).size)
    }

    // =========================================================================
    // 3 — the T3 guard is intact, and #1095 makes the loss LOUD
    // =========================================================================

    @Test
    fun `an un-arrived dropoff at the summary absorbs nothing, mints nothing and trips the tripwire`() {
        val navigating = drop(arrived = null)
        val onDrop = onDropoff(navigating).copy(lastActedFlow = Flow.TaskDropoffNavigation)

        val afterSummary = step(onDrop, Flow.TaskDropoffNavigation, summaryFrame(summaryAt))
        val end = afterSummary.pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertNull(
            "no arrival, no retire — the arrival gate (#615) keeps this on the T3 side",
            end.absorbedRetireSince,
        )

        val wake = graceWake(end)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)
        assertEquals(
            "the T3 force-stamp still stamps the teardown clock",
            end.since, afterCommit.recentTasks.single { it.taskId == "d1" }.completedAt,
        )

        val effects = completions(afterSummary, afterCommit, wake)
        assertEquals(
            "the amdt-#5 T3 guard still refuses an unqualified force-stamp",
            0, deliveries(effects).size,
        )
        val tripwires = mismatches(effects)
        assertEquals("#1095: the stranded accept is loud now, not silent", 1, tripwires.size)
        val payload = tripwires.single().event.payload as JobAcceptMismatchPayload
        assertEquals("the single-accept floor is lifted on a session end", 1, payload.acceptedCount)
        assertEquals("nothing was accounted", 0, payload.accountedCount)
        assertEquals(
            "attributed to the session that ended, never to a later one",
            "dash-A", tripwires.single().event.sessionId,
        )
    }

    // =========================================================================
    // 4 — the mode arm absorbs too
    // =========================================================================

    @Test
    fun `an offline flash over a live retire absorbs it and its commit mints`() {
        val onDrop = onDropoff()
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val retire = afterIdle.pendingDestructive!!
        assertEquals(DestructiveKind.TASK_RETIRE, retire.kind)

        // An Offline-hinted frame — non-authoritative, the long grace.
        val offline = screen(Flow.Idle, idleAt + 1_000L, modeHint = Mode.Offline)
        val afterOffline = step(afterIdle, Flow.Idle, offline)
        val end = afterOffline.pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertFalse("an offline flash is NOT authoritative", end.authoritative)
        assertEquals(
            "the Offline arm no longer overwrites a standing TASK_RETIRE — it absorbs it (#1078)",
            retire.since, end.absorbedRetireSince,
        )

        val wake = graceWake(end)
        val afterCommit = step(afterOffline, Flow.Idle, wake)
        assertEquals(idleAt, afterCommit.recentTasks.single { it.taskId == "d1" }.completedAt)
        assertEquals(
            "the honored teardown mints the completion",
            1, deliveries(completions(afterOffline, afterCommit, wake)).size,
        )
    }

    @Test
    fun `an offline flash with NO retire standing absorbs nothing (rule 2 is summary-only)`() {
        val onDrop = onDropoff()
        val offline = screen(Flow.Idle, idleAt, modeHint = Mode.Offline)
        val end = step(onDrop, Flow.TaskDropoffArrived, offline).pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertNull(
            "an offline flash is not evidence a dash ended — an ARRIVED drop is not absorbed here",
            end.absorbedRetireSince,
        )
    }

    // =========================================================================
    // 5 — the cancel path takes the absorbed value with it
    // =========================================================================

    @Test
    fun `a task frame inside the window cancels the end and the absorbed value goes with it`() {
        val onDrop = onDropoff()
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(summaryAt))
        assertEquals(idleAt, afterSummary.pendingDestructive?.absorbedRetireSince)

        // A contradicting task-flow frame INSIDE the 2.5 s window — a misrecognized summary.
        val backOnTask = step(afterSummary, Flow.SessionEnded, dropoffFrame(summaryAt + 1_000L))

        assertNull("the misrecognized end is cancelled outright", backOnTask.pendingDestructive)
        assertEquals("the task is still active", "d1", backOnTask.activeTask?.taskId)
        assertNull("nothing was completed", backOnTask.activeTask?.completedAt)
        assertEquals("the session lives on", "dash-A", backOnTask.session?.sessionId)
        assertTrue("no drop landed in recentTasks", backOnTask.recentTasks.none { it.taskId == "d1" })
        assertEquals(
            "no completion is emitted on the cancel step",
            0,
            deliveries(completions(afterSummary, backOnTask, dropoffFrame(summaryAt + 1_000L))).size,
        )
    }

    // =========================================================================
    // 6 — the tighten branch gains rule 2's value
    // =========================================================================

    @Test
    fun `an offline-armed end tightened by the summary picks up the arrived drop`() {
        val onDrop = onDropoff()
        // An offline flash first: a SESSION_END with nothing absorbed (no retire stood).
        val afterOffline = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt, modeHint = Mode.Offline))
        assertNull(afterOffline.pendingDestructive?.absorbedRetireSince)

        // Then the summary tightens it — and rule 2 now applies, because THIS signal is authoritative.
        val afterSummary = step(afterOffline, Flow.Idle, summaryFrame(summaryAt))
        val end = afterSummary.pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertTrue(end.authoritative)
        assertEquals("the tighten keeps the earliest destructive `since`", idleAt, end.since)
        assertEquals("…and gains the absorbed value the tighten frame supplies", summaryAt, end.absorbedRetireSince)
        assertEquals(
            "the deadline is the `minOf` of the two windows — here the offline arm's, which " +
                "already expires before the summary's would",
            minOf(idleAt + 10_000L, summaryDeadline), end.deadline,
        )

        val wake = graceWake(end)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)
        assertEquals(summaryAt, afterCommit.recentTasks.single { it.taskId == "d1" }.completedAt)
        assertEquals(1, deliveries(completions(afterSummary, afterCommit, wake)).size)
    }

    @Test
    fun `an already-absorbed value survives a later tighten`() {
        val onDrop = onDropoff()
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(summaryAt))
        assertEquals(idleAt, afterSummary.pendingDestructive?.absorbedRetireSince)

        // A second summary frame re-tightens; the earliest absorbed evidence must win.
        val again = step(afterSummary, Flow.SessionEnded, summaryFrame(summaryAt + 500L))
        assertEquals(
            "the earliest absorbed retire is the honest one, exactly as `since` is",
            idleAt, again.pendingDestructive?.absorbedRetireSince,
        )
    }
}
