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
import cloud.trotter.dashbuddy.domain.state.JobReceiptAnchors
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingSessionPay
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
 * **Only a PROVENANCED retire is absorbable** (round 2). `armedFromFlow` (#596) has to say the task
 * it retires actually finished: an `OfferPresented` retire is the dasher stepping off to deliberate
 * on an add-on (undelivered), a `PostTask` one's completion is already minted on the receipt's exit
 * frame (honoring it again double-mints), and a null provenance is not evidence. An arrival-only
 * "rule 2" — absorb at the summary whenever an ARRIVED drop is still active — was designed and
 * REJECTED: it fabricates a completion for a drop cancelled through an uncaptured path, or for a
 * misrecognized summary. Fail-null beats fail-wrong (#745).
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

    private fun offerFrame(at: Long) = screen(Flow.OfferPresented, at)

    private fun receiptFrame(at: Long) = screen(
        Flow.PostTask, at,
        parsed = ParsedFields.PostTaskFields(totalPay = 21.0, isExpanded = true),
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
    // 2 — the REJECTED rule 2: an arrived drop with no retire absorbs NOTHING
    // =========================================================================

    @Test
    fun `an ARRIVED dropoff at the summary with NO retire standing absorbs nothing and mints nothing`() {
        // Round 2 rejected absorbing on arrival alone. The reasoning that tempted it — the platform
        // does not let a dasher end a dash with an active order — is not evidence the machine
        // WATCHED: the same shape is produced by a drop cancelled or unassigned through a path the
        // sensor never captured, and by a misrecognized summary. So this stays on the T3 side and is
        // made loud by the #1095 tripwire instead of quietly priced.
        val onDrop = onDropoff()
        val afterSummary = step(onDrop, Flow.TaskDropoffArrived, summaryFrame(summaryAt))
        val end = afterSummary.pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertNull(
            "arrival alone is not a retire — nothing is absorbed (rule 2 REJECTED)",
            end.absorbedRetireSince,
        )

        val wake = graceWake(end)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)
        assertEquals(
            "the teardown force-stamps at its own clock",
            end.since, afterCommit.recentTasks.single { it.taskId == "d1" }.completedAt,
        )
        val effects = completions(afterSummary, afterCommit, wake)
        assertEquals("and the T3 guard refuses the mint", 0, deliveries(effects).size)

        // #1095 round 2: THIS is the lost-drop shape the lifted floor names — session ended, no
        // qualified completion, and a drop the dasher actually stood at. Loud instead of silent.
        val tripwires = mismatches(effects)
        assertEquals("the loss is LOUD instead (#1095)", 1, tripwires.size)
        val payload = tripwires.single().event.payload as JobAcceptMismatchPayload
        assertEquals("the single-accept floor is lifted for this shape", 1, payload.acceptedCount)
        assertEquals("nothing was accounted", 0, payload.accountedCount)
        assertEquals(
            "attributed to the session that ended, never to a later one",
            "dash-A", tripwires.single().event.sessionId,
        )
    }

    // =========================================================================
    // 2b — provenance: which retires a teardown may honor
    // =========================================================================

    @Test
    fun `an OfferPresented-armed retire is NOT absorbable — an add-on deliberation is not a delivery`() {
        // The dasher left the drop for a mid-route add-on offer. That drop is undelivered — the same
        // reason `retireActiveTask` refuses to close the job on this provenance.
        val onDrop = onDropoff()
        val afterOffer = step(onDrop, Flow.TaskDropoffArrived, offerFrame(idleAt))
        val retire = afterOffer.pendingDestructive!!
        assertEquals(DestructiveKind.TASK_RETIRE, retire.kind)
        assertEquals(Flow.OfferPresented, retire.armedFromFlow)

        val afterOffline = step(afterOffer, Flow.OfferPresented, screen(Flow.Idle, idleAt + 1_000L, modeHint = Mode.Offline))
        val end = afterOffline.pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertNull(
            "an offer-deliberation retire authorizes no completion",
            end.absorbedRetireSince,
        )

        val wake = graceWake(end)
        val afterCommit = step(afterOffline, Flow.Idle, wake)
        assertEquals(
            "nothing is minted for a drop the dasher never finished",
            0, deliveries(completions(afterOffline, afterCommit, wake)).size,
        )
    }

    @Test
    fun `a PostTask-armed retire is NOT absorbable — its completion is already minted on the exit`() {
        // The receipt arms its own TASK_RETIRE. The PostTask-EXIT frame mints the completion; if the
        // teardown honored the same retire it would emit a SECOND raw DELIVERY_COMPLETED for one
        // task — invisible live behind the per-task `effects_fired` key, a double-count in replay.
        // Counted across the WHOLE sequence, exactly one completion may exist.
        val onDrop = onDropoff()
        val receipt = receiptFrame(idleAt)
        val afterReceipt = step(onDrop, Flow.TaskDropoffArrived, receipt)
        val retire = afterReceipt.pendingDestructive!!
        assertEquals(DestructiveKind.TASK_RETIRE, retire.kind)
        assertEquals(Flow.PostTask, retire.armedFromFlow)

        // The summary is the PostTask EXIT — the mint fires on this very step.
        val summary = summaryFrame(idleAt + 1_000L)
        val afterSummary = step(afterReceipt, Flow.PostTask, summary)
        val end = afterSummary.pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertNull(
            "a receipt-armed retire is not absorbable — its completion is already minted",
            end.absorbedRetireSince,
        )
        val exitEffects = effectMap.diff(
            state(afterReceipt, Flow.PostTask), state(afterSummary, Flow.SessionEnded), summary,
        ).filterIsInstance<AppEffect.LogEvent>()

        val wake = graceWake(end)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)
        val teardownEffects = completions(afterSummary, afterCommit, wake)

        assertEquals(
            "exactly ONE raw DELIVERY_COMPLETED across the whole sequence — the exit mint",
            1, deliveries(exitEffects).size + deliveries(teardownEffects).size,
        )
        assertEquals("…and it is the exit's", 1, deliveries(exitEffects).size)
    }

    // =========================================================================
    // 2c — an authoritative abandon disowns an absorbed retire
    // =========================================================================

    @Test
    fun `a task-unassigned frame on the commit clears the absorbed retire and nothing mints`() {
        val onDrop = onDropoff()
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(summaryAt))
        assertEquals(idleAt, afterSummary.pendingDestructive?.absorbedRetireSince)

        // The abandon lands 1 ms past the deadline — the frame that commits the end. `modeHint` is
        // ONLINE because the shipped `task:unassigned` rule always ships one (#736), which means the
        // mode arm mints a FRESH session on this very step: the round-3 attribution case.
        val abandon = screen(Flow.TaskUnassigned, summaryDeadline + 1L, modeHint = Mode.Online)
        val afterCommit = step(afterSummary, Flow.SessionEnded, abandon)

        assertNotEquals(
            "the dash that was ending really ended — a fresh session took its place",
            "dash-A", afterCommit.session?.sessionId,
        )
        assertEquals(
            "the drop is marked ABANDONED, which is what makes the T3 refusal bite",
            summaryDeadline + 1L,
            afterCommit.recentTasks.single { it.taskId == "d1" }.unassignedAt,
        )
        val unassignEvents = completions(afterSummary, afterCommit, abandon)
            .filter { it.event.type == AppEventType.TASK_UNASSIGNED }
        assertEquals("the abandon is recorded once", 1, unassignEvents.size)
        assertEquals(
            "…and booked to the session it happened in, not the one just minted",
            "dash-A", unassignEvents.single().event.sessionId,
        )
        assertEquals(
            "the authoritative abandon wins: nothing is minted for the order it abandoned",
            0, deliveries(completions(afterSummary, afterCommit, abandon)).size,
        )
    }

    // =========================================================================
    // 3 — the T3 guard is intact, and #1095 makes the loss LOUD
    // =========================================================================

    @Test
    fun `an un-arrived dropoff at the summary absorbs nothing and mints nothing`() {
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
        assertEquals(
            "#1095 round 2: the lifted floor names the drop the dasher STOOD at. This one was never " +
                "arrived — an early-offline bail mid-route, which is the class the 2-floor exists " +
                "for. The ARRIVED variant is loud; see the rule-2 test above.",
            0, mismatches(effects).size,
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
    fun `an offline flash with NO retire standing absorbs nothing`() {
        val onDrop = onDropoff()
        val offline = screen(Flow.Idle, idleAt, modeHint = Mode.Offline)
        val end = step(onDrop, Flow.TaskDropoffArrived, offline).pendingDestructive!!
        assertEquals(DestructiveKind.SESSION_END, end.kind)
        assertNull(
            "there is no retire to absorb, and an arrived drop is never absorbed on its own",
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
    // 6 — the tighten branch keeps what it already absorbed
    // =========================================================================

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

    // =========================================================================
    // 7 — the closed job's completion belongs to the session it was earned in
    // =========================================================================

    @Test
    fun `a same-step end-A-plus-mint-B attributes the honored delivery to session A`() {
        // The fielded shape behind round 2's finding 4: the app is killed / goes offline mid-job with
        // a retire standing, the offline arm absorbs it, and the NEXT dash's first Online frame lands
        // past the deadline. On that one step the lazy expiry ends session A (honoring the retire and
        // minting the delivery) and the mode arm then mints session B — so a `next`-first read put
        // A's delivery, and A's running total, into B.
        val onDrop = onDropoff()
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val afterOffline = step(afterIdle, Flow.Idle, screen(Flow.Idle, idleAt + 1_000L, modeHint = Mode.Offline))
        val end = afterOffline.pendingDestructive!!
        assertEquals(idleAt, end.absorbedRetireSince)

        // One frame, past the deadline, implying Online: ends A and starts B.
        val nextDashFrame = screen(Flow.Idle, end.deadline + 1_000L, modeHint = Mode.Online)
        val afterNextDash = step(afterOffline, Flow.Idle, nextDashFrame)

        assertEquals("session B was minted on the same step", true, afterNextDash.session != null)
        assertNotEquals("…and it is NOT session A", "dash-A", afterNextDash.session?.sessionId)

        val effects = effectMap.diff(
            state(afterOffline, Flow.Idle), state(afterNextDash, Flow.Idle), nextDashFrame,
        ).filterIsInstance<AppEffect.LogEvent>()

        val completed = effects.single { it.event.type == AppEventType.DELIVERY_COMPLETED }
        assertEquals(
            "the honored delivery is attributed to the dash it was earned in",
            "dash-A", completed.event.sessionId,
        )
        assertEquals(
            "…and so is its running total",
            21.0, (completed.event.payload as DeliveryPayload).sessionEarningsAtCompletion!!, 0.0001,
        )
        assertEquals(
            "B still starts on this step",
            1, effects.count { it.event.type == AppEventType.DASH_START },
        )
        // NOT asserted here: the `DASH_STOP` on this step is attributed to the freshly minted
        // session B, because `ModeEffects` reads the NEXT region's session. That is a separate
        // emitter and a pre-existing behaviour, untouched by #1078 — recorded rather than silently
        // changed. The MONEY (the delivery and its running total) is what this fix moves.
    }

    // =========================================================================
    // 8 — round 3: an exit-minted completion is never emitted twice
    // =========================================================================

    @Test
    fun `a receipt, a return to the drop, then an idle retire and a dash end mint exactly ONE completion`() {
        // The re-entry shape the `armedFromFlow` denylist structurally cannot see. The receipt arms a
        // PostTask retire; going BACK to the dropoff screen cancels it and mints the completion on
        // that PostTask EXIT while leaving the task active; an idle frame then arms a FRESH, fully
        // absorbable `Idle` retire, the summary absorbs it, and the teardown honors it. Only
        // [mintedAtPostTaskExit] knows the mint already happened.
        val onDrop = onDropoff()
        val receipt = receiptFrame(100_000L)
        val afterReceipt = step(onDrop, Flow.TaskDropoffArrived, receipt)
        assertEquals(Flow.PostTask, afterReceipt.pendingDestructive!!.armedFromFlow)

        // Back to the same dropoff screen: this IS the PostTask exit — the mint fires here.
        val backOnDrop = dropoffFrame(101_000L)
        val afterBack = step(afterReceipt, Flow.PostTask, backOnDrop)
        assertNull("the returning task frame cancels the receipt retire", afterBack.pendingDestructive)
        assertEquals("the task is still ACTIVE — its completedAt is not stamped yet", "d1", afterBack.activeTask?.taskId)
        val exitEffects = effectMap.diff(
            state(afterReceipt, Flow.PostTask), state(afterBack, Flow.TaskDropoffArrived), backOnDrop,
        ).filterIsInstance<AppEffect.LogEvent>()
        assertEquals("the PostTask exit mints the completion", 1, deliveries(exitEffects).size)
        assertTrue(
            "…and the durable anchors record that it happened",
            afterBack.mintedAtPostTaskExit("d1"),
        )

        // A fresh, absorbable Idle retire — then the dash ends over it.
        val afterIdle = step(afterBack, Flow.TaskDropoffArrived, screen(Flow.Idle, 102_000L))
        assertEquals(Flow.Idle, afterIdle.pendingDestructive!!.armedFromFlow)
        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(103_000L))
        assertEquals(
            "the fresh retire IS absorbed — provenance says nothing about the earlier mint",
            102_000L, afterSummary.pendingDestructive!!.absorbedRetireSince,
        )
        val wake = graceWake(afterSummary.pendingDestructive!!)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)
        val teardownEffects = completions(afterSummary, afterCommit, wake)

        assertEquals(
            "exactly ONE raw DELIVERY_COMPLETED across the WHOLE sequence — the exit's",
            1, deliveries(exitEffects).size + deliveries(teardownEffects).size,
        )
        assertEquals("the teardown adds none", 0, deliveries(teardownEffects).size)
    }

    @Test
    fun `a receipted delivery whose dash ends before the retire expires raises no tripwire`() {
        // A completely ordinary receipted delivery: arrived drop → receipt → the dasher ends the dash
        // before the receipt's 8 s retire window closes. The exit mint happened, so nothing was lost
        // — but until round 3 the tripwire's masked evidence said "no completed dropoff" and the
        // lifted single-accept floor fired a false 1-of-0 alarm on it.
        val onDrop = onDropoff()
        val receipt = receiptFrame(100_000L)
        val afterReceipt = step(onDrop, Flow.TaskDropoffArrived, receipt)

        val summary = summaryFrame(101_000L)
        val afterSummary = step(afterReceipt, Flow.PostTask, summary)
        val exitEffects = effectMap.diff(
            state(afterReceipt, Flow.PostTask), state(afterSummary, Flow.SessionEnded), summary,
        ).filterIsInstance<AppEffect.LogEvent>()
        assertEquals("the SessionEnded frame is the PostTask exit — it mints", 1, deliveries(exitEffects).size)
        assertTrue(
            "the exit is recorded durably — this is what the tripwire's evidence reads",
            afterSummary.mintedAtPostTaskExit("d1"),
        )

        val wake = graceWake(afterSummary.pendingDestructive!!)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)
        val teardownEffects = completions(afterSummary, afterCommit, wake)

        assertEquals(
            "exactly one completion across the sequence",
            1, deliveries(exitEffects).size + deliveries(teardownEffects).size,
        )
        assertEquals(
            "and NO false lost-drop alarm on a delivery that was recorded",
            0, mismatches(exitEffects).size + mismatches(teardownEffects).size,
        )
    }

    // =========================================================================
    // 9 — round 3: an unassign INSIDE the window disowns too
    // =========================================================================

    @Test
    fun `a task-unassigned frame INSIDE the summary window disowns the absorbed retire`() {
        // Before round 3 this frame fell straight through `updateLifecycle`'s `Mode.Offline` early
        // return — the summary implied Offline, so `abandonActiveTask` was unreachable — and the
        // absorbed value survived to fabricate a completion at the wake.
        val onDrop = onDropoff()
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(summaryAt, modeHint = Mode.Offline))
        assertEquals(idleAt, afterSummary.pendingDestructive?.absorbedRetireSince)

        // 600 ms BEFORE the deadline — hint-less, so the mode arm cannot rescue it either.
        val abandon = screen(Flow.TaskUnassigned, summaryAt + 600L)
        val afterAbandon = step(afterSummary, Flow.SessionEnded, abandon)
        assertNull(
            "the absorbed retire is disowned in-window",
            afterAbandon.pendingDestructive?.absorbedRetireSince,
        )
        assertNotNull("the end itself still stands", afterAbandon.pendingDestructive)
        assertEquals(
            "and the task carries the abandon",
            summaryAt + 600L, afterAbandon.activeTask?.unassignedAt,
        )

        val wake = graceWake(afterAbandon.pendingDestructive!!)
        val afterCommit = step(afterAbandon, Flow.SessionEnded, wake)
        assertNull("the dash ends", afterCommit.session)
        assertEquals(
            "nothing is minted for an order the platform said was abandoned",
            0, deliveries(completions(afterAbandon, afterCommit, wake)).size,
        )
        assertNotNull(
            "the abandon survives into recentTasks",
            afterCommit.recentTasks.single { it.taskId == "d1" }.unassignedAt,
        )
    }

    @Test
    fun `a task-unassigned frame exactly AT the deadline disowns too`() {
        // Deadline equality: a FRAME lapses a grace only strictly PAST the deadline (#1054), so this
        // one reaches the in-window disown rather than the expiry branch. Either path must disown.
        val onDrop = onDropoff()
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(summaryAt, modeHint = Mode.Offline))
        val deadline = afterSummary.pendingDestructive!!.deadline

        val abandon = screen(Flow.TaskUnassigned, deadline)
        val afterAbandon = step(afterSummary, Flow.SessionEnded, abandon)
        assertNull(
            "at the tie the disown still runs",
            afterAbandon.pendingDestructive?.absorbedRetireSince,
        )

        val wake = graceWake(afterAbandon.pendingDestructive!!)
        val afterCommit = step(afterAbandon, Flow.SessionEnded, wake)
        assertNull("the dash ends", afterCommit.session)
        assertEquals(
            "no completion is fabricated at the tie either",
            0, deliveries(completions(afterAbandon, afterCommit, wake)).size,
        )
    }

    // =========================================================================
    // 10 — round 3: an in-session close reads THIS step's settled total
    // =========================================================================

    @Test
    fun `an in-session close publishes the running total the SAME observation settled`() {
        // Round 2's unconditional prev-first was a regression here, and the test has to be arranged
        // so that it CANNOT pass under prev-first: both deadlines lapse on ONE observation while the
        // session survives, so `prev` still holds \$0.00 and only `next` holds the settled \$21.00.
        // (Round 3's first attempt settled the park on an EARLIER frame, by which point prev and
        // next agreed — prev-first passed it too.)
        val drop = drop()
        val armed = PlatformRegion(
            platform = platform,
            mode = Mode.Online,
            session = session.copy(runningEarnings = 0.0),
            activeJob = job(drop),
            activeTask = drop,
            lastActedFlow = Flow.Idle,
            // A retire armed by the idle frame that also parked the read.
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE,
                since = idleAt,
                deadline = idleAt + 10_000L,
                armedFromFlow = Flow.Idle,
                wakeId = 11L,
            ),
            pendingSessionPay = PendingSessionPay(
                value = 21.0, flow = Flow.Idle, deadline = idleAt + 3_000L,
                since = idleAt, wakeId = 12L,
            ),
        )
        assertEquals("the fixture starts with a stale total", 0.0, armed.session!!.runningEarnings, 0.0001)

        // ONE idle frame, past BOTH deadlines: the destructive expiry retires the drop and closes the
        // job (T1), and the settle park commits — on the same observation, with the session alive.
        val oneFrame = screen(Flow.Idle, idleAt + 11_000L)
        val after = step(armed, Flow.Idle, oneFrame)
        assertEquals("the session survives the step", "dash-A", after.session?.sessionId)
        assertEquals("…and the park settled on THIS frame", 21.0, after.session!!.runningEarnings, 0.0001)
        assertNull("…which is also the frame that closed the job", after.activeJob)

        val row = deliveries(completions(armed, after, oneFrame)).single()
        assertEquals(
            "the completion carries the total this step settled, not the stale pre-settle figure " +
                "(prev-first would publish \$0.00)",
            21.0, row.sessionEarningsAtCompletion!!, 0.0001,
        )
    }

    @Test
    fun `closingSession falls back to the newly minted session when nothing was live before the step`() {
        // The first captured frame of a dash can be a task screen (offer/accept never captured): the
        // session and the task mint on ONE frame. There is no closing session to attribute to, so the
        // task edge must carry the NEW session — never null (round 3 follow-up at the session tier).
        val prev = PlatformRegion(Platform.DoorDash)
        val next = PlatformRegion(Platform.DoorDash, session = Session("dash-B", startedAt = 1L))
        assertEquals("dash-B", closingSession(prev, next)?.sessionId)
        // …and the two rules it sits beside are unchanged.
        val a = Session("dash-A", startedAt = 1L)
        assertEquals("survives → next", "dash-A", closingSession(PlatformRegion(Platform.DoorDash, session = a), PlatformRegion(Platform.DoorDash, session = a))?.sessionId)
        assertEquals("ends → prev", "dash-A", closingSession(PlatformRegion(Platform.DoorDash, session = a), PlatformRegion(Platform.DoorDash))?.sessionId)
    }

    // =========================================================================
    // 11 — round 4: absorption also needs the #615 arrival
    // =========================================================================

    @Test
    fun `an Idle-armed retire on an UN-ARRIVED dropoff is not absorbable`() {
        // Provenance says the dasher LEFT the task; it does not say the order was delivered. The
        // retire's own T1 close already demands arrival (`isJobPhysicallyComplete`), and absorption
        // was walking past it — minting a DELIVERY_COMPLETED with a null arrival and a #691 estimate
        // riding it for a drop the dasher was still navigating to.
        val navigating = drop(arrived = null)
        val onDrop = onDropoff(navigating).copy(lastActedFlow = Flow.TaskDropoffNavigation)

        val afterIdle = step(onDrop, Flow.TaskDropoffNavigation, screen(Flow.Idle, idleAt))
        val retire = afterIdle.pendingDestructive!!
        assertEquals("the retire itself arms exactly as before", DestructiveKind.TASK_RETIRE, retire.kind)
        assertEquals(Flow.Idle, retire.armedFromFlow)

        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(summaryAt))
        assertNull(
            "a watched retire is necessary but NOT sufficient — the doorstep evidence is missing",
            afterSummary.pendingDestructive?.absorbedRetireSince,
        )

        val wake = graceWake(afterSummary.pendingDestructive!!)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)
        val rows = deliveries(completions(afterSummary, afterCommit, wake))
        assertEquals("nothing is minted for a drop never arrived at", 0, rows.size)
    }

    @Test
    fun `an ARRIVED dropoff under an Idle-armed retire still absorbs (the gate is additive)`() {
        // The guard rail on the guard rail: adding the arrival condition must not break rule 1's own
        // shape, which is the whole 09-08 fix.
        val onDrop = onDropoff()
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(summaryAt))
        assertEquals(idleAt, afterSummary.pendingDestructive?.absorbedRetireSince)
    }

    // =========================================================================
    // 12 — round 4: the exit-mint record is PER TASK
    // =========================================================================

    /** A region whose PostTask exit has just been recorded for [taskId], as the stepper writes it. */
    private fun afterExitOf(taskId: String, task: Task, receiptAt: Long): PlatformRegion {
        val onReceipt = PlatformRegion(
            platform = platform,
            mode = Mode.Online,
            session = session,
            activeJob = job(task),
            activeTask = task,
            lastActedFlow = Flow.PostTask,
            lastAnnouncedPostTaskTaskId = taskId,
            lastPostTaskFields = ParsedFields.PostTaskFields(totalPay = 21.0, isExpanded = true),
            jobReceiptAnchors = JobReceiptAnchors(jobId = "J1", firstEnteredAt = receiptAt),
        )
        return step(onReceipt, Flow.PostTask, screen(Flow.Idle, receiptAt + 1_000L))
    }

    @Test
    fun `a SECOND drop is never claimed by the FIRST drop's exit (the stacked silent loss)`() {
        // Round 3 derived "already minted" from the job-wide `exitedPostTask` flag AND
        // `lastAnnouncedPostTaskTaskId`. In a stacked job D1's exit latches the flag and D2's receipt
        // then MOVES the announce id — so the derivation claimed D2 was minted before D2 ever
        // exited, and D2's completion was skipped at the close. A silent loss, the same class #1078
        // is about. The record is per-task now.
        val afterD1Exit = afterExitOf("d1", drop(), 100_000L)
        assertTrue("D1's exit is recorded", afterD1Exit.mintedAtPostTaskExit("d1"))

        // D2's receipt lands and moves the announce id — the exact overwrite that fooled round 3.
        val withD2Announce = afterD1Exit.copy(lastAnnouncedPostTaskTaskId = "d2")
        assertTrue("D1's record survives the overwrite", withD2Announce.mintedAtPostTaskExit("d1"))
        assertFalse(
            "D2 never exited, so nothing was minted for it — round 3 said TRUE here and lost the row",
            withD2Announce.mintedAtPostTaskExit("d2"),
        )
    }

    @Test
    fun `a PostTask frame that parsed NOTHING still records its exit mint`() {
        // The mirror failure: a `ParsedFields.None` PostTask never sets an announce id, so round 3's
        // derivation answered NOT-minted and the double emission it exists to prevent survived. The
        // subject resolver names the ACTIVE dropoff regardless of what the frame parsed.
        val onReceipt = PlatformRegion(
            platform = platform,
            mode = Mode.Online,
            session = session,
            activeJob = job(drop()),
            activeTask = drop(),
            lastActedFlow = Flow.PostTask,
            jobReceiptAnchors = JobReceiptAnchors(jobId = "J1", firstEnteredAt = 100_000L),
        )
        assertNull("no announce id was ever set", onReceipt.lastAnnouncedPostTaskTaskId)

        val exitFrame = screen(Flow.Idle, 101_000L)
        val afterExit = step(onReceipt, Flow.PostTask, exitFrame)
        val exitEffects = effectMap.diff(
            state(onReceipt, Flow.PostTask), state(afterExit, Flow.Idle), exitFrame,
        ).filterIsInstance<AppEffect.LogEvent>()
        assertEquals("the exit mints", 1, deliveries(exitEffects).size)
        assertTrue("…and it is recorded", afterExit.mintedAtPostTaskExit("d1"))

        // Now the #1078 teardown path over a fresh, absorbable Idle retire.
        val afterSummary = step(afterExit, Flow.Idle, summaryFrame(102_000L))
        val wake = graceWake(afterSummary.pendingDestructive!!)
        val afterCommit = step(afterSummary, Flow.SessionEnded, wake)
        val teardownEffects = completions(afterSummary, afterCommit, wake)
        assertEquals(
            "exactly ONE raw completion across the whole sequence",
            1, deliveries(exitEffects).size + deliveries(teardownEffects).size,
        )
    }

    // =========================================================================
    // 13 — round 4: a NEW-task edge belongs to the session it started in
    // =========================================================================

    @Test
    fun `session B's first pickup is attributed to B, not to the dash that ended on the same step`() {
        // Round 3 routed every `diffTask` edge through `closingSession`, which books work that BEGINS
        // in the new dash to the old one. The probe: A's absorbed summary pending is still standing
        // when B's first pickup-navigation frame lands past the deadline — the lazy expiry ends A
        // and the mode arm mints B and its pickup on that one step.
        val onDrop = onDropoff()
        val afterIdle = step(onDrop, Flow.TaskDropoffArrived, screen(Flow.Idle, idleAt))
        val afterSummary = step(afterIdle, Flow.Idle, summaryFrame(summaryAt))
        assertEquals(idleAt, afterSummary.pendingDestructive?.absorbedRetireSince)

        val pickupFrame = screen(
            Flow.TaskPickupNavigation, summaryDeadline + 5_000L,
            parsed = ParsedFields.TaskFields(
                storeName = "Next Merchant",
                phase = TaskPhase.PICKUP,
                subFlow = TaskSubFlow.NAVIGATION,
            ),
        )
        val afterPickup = step(afterSummary, Flow.SessionEnded, pickupFrame)
        val newSessionId = afterPickup.session?.sessionId
        assertNotNull("a fresh dash started on this step", newSessionId)
        assertNotEquals("…and it is not the one that ended", "dash-A", newSessionId)

        val effects = effectMap.diff(
            state(afterSummary, Flow.SessionEnded), state(afterPickup, Flow.TaskPickupNavigation),
            pickupFrame,
        ).filterIsInstance<AppEffect.LogEvent>()

        val navStarted = effects.single { it.event.type == AppEventType.PICKUP_NAV_STARTED }
        assertEquals(
            "B's own pickup belongs to B — a NEW-task edge is not a close-step emitter",
            newSessionId, navStarted.event.sessionId,
        )
        val confirmed = effects.filter { it.event.type == AppEventType.DELIVERY_CONFIRMED }
        confirmed.forEach {
            assertEquals(
                "…while the retiring predecessor's confirmation stays with the dash it happened in",
                "dash-A", it.event.sessionId,
            )
        }
        assertEquals(
            "and A's honored delivery is still A's",
            "dash-A",
            effects.single { it.event.type == AppEventType.DELIVERY_COMPLETED }.event.sessionId,
        )
    }
}
