package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #762 D2 — the phase-less coarse-in-job flow [Flow.TaskActive] and the per-platform accept grace.
 *
 * `task:active` (Uber's `on_job_view`) is a task flow for job-lifecycle purposes (it consumes an
 * accepted offer into a costed job and holds mode Online) but is DELIBERATELY phase-less: it must
 * never mint / displace / complete / resume a task, and interleaving it between real leg frames must
 * be inert. Accept-grace is per-platform (DoorDash 120s, Uber 600s) via `GraceConfig.acceptGraceMs`.
 */
class ActiveFlowTest {

    private val stepper = PlatformRegionStepper()

    /** Code-default policy: Uber accept-grace = 600s, DoorDash = 120s (no user overrides). */
    private val policy = TransitionPolicy()

    private fun region(platform: Platform = Platform.DoorDash) = PlatformRegion(
        platform = platform,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
    )

    private fun order(i: Int, store: String) = ParsedOrder(
        orderIndex = i, orderType = OrderType.PICKUP, storeName = store,
        itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
    )

    private fun offerObs(t: Long, hash: String, store: String, pay: Double = 14.0) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "offer",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(
                offerHash = hash, payAmount = pay, distanceMiles = 5.0, timeToCompleteMinutes = 20L,
                orders = listOf(order(0, store)),
            ),
        ),
    )

    private fun acceptClick(t: Long) = Observation.Click(
        timestamp = t, captureId = null, ruleId = "accept",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
    )

    /** The coarse in-job surface (Uber `on_job_view`) — declares `task:active`, modeHint online. */
    private fun activeTripObs(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "uber.screen.active_trip",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskActive, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun pickupObs(t: Long, store: String, sub: TaskSubFlow) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "pickup",
        metadata = ReplayMetadata.EMPTY,
        flow = if (sub == TaskSubFlow.ARRIVED) Flow.TaskPickupArrived else Flow.TaskPickupNavigation,
        modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = sub, storeName = store),
    )

    private fun dropoffNav(t: Long, cx: String) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "dropoff",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskDropoffNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION, customerNameHash = cx),
    )

    /** Drive a sequence through the stepper, deriving prev/next flow per obs (as StateMachine does). */
    private fun drive(start: PlatformRegion, vararg obs: Observation): PlatformRegion {
        var region = start
        var prevFlow = FlowRegion()
        for (o in obs) {
            val f = (o as? Observation.FlowObservation)?.flow
            val nextFlow = if (f != null) prevFlow.copy(flow = f, activePlatform = region.platform) else prevFlow
            region = stepper.step(region, prevFlow, nextFlow, o, policy)
            prevFlow = nextFlow
        }
        return region
    }

    /** One step with explicit prev/next flow, so intermediate inertness can be inspected. */
    private fun step(r: PlatformRegion, prev: Flow?, o: Observation): Pair<PlatformRegion, Flow?> {
        val prevFlow = FlowRegion(flow = prev ?: Flow.Idle, activePlatform = r.platform)
        val f = (o as? Observation.FlowObservation)?.flow
        val nextFlow = if (f != null) prevFlow.copy(flow = f) else prevFlow
        return stepper.step(r, prevFlow, nextFlow, o, policy) to (f ?: prev)
    }

    // =====================================================================
    // (a) accept → task:active consumes into a costed job, mints NO task
    // =====================================================================

    @Test
    fun `a task-active frame consumes an accepted offer into a costed job with no task minted`() {
        val r = drive(
            region(Platform.Uber),
            offerObs(1_000L, "o1", "Bill Miller BBQ", pay = 16.0),
            acceptClick(1_050L),
            activeTripObs(1_200L), // the coarse in-job surface — consumes the accept
        )
        val job = r.activeJob
        assertNotNull("task:active mints the job from the accepted survivor", job)
        assertTrue("economics recovered (costed job)", job!!.acceptedOffers.isNotEmpty())
        assertEquals("gross pay recovered", 16.0, job.totalPayAmount, 0.001)
        assertNull("no active task minted — task:active is phase-less", r.activeTask)
        assertTrue("no completed task either", r.recentTasks.isEmpty())
        assertNull("the survivor is consumed", r.pendingOffers.firstOrNull { it.acceptedAt != null })
    }

    // =====================================================================
    // (b) INERTNESS — task:active interleaved between real leg frames
    // =====================================================================

    @Test
    fun `task-active interleaved between pickup-arrived and dropoff does not displace, complete, or retire`() {
        // Build up to an arrived pickup.
        var r = drive(
            region(Platform.Uber),
            offerObs(1_000L, "o1", "Bill Miller BBQ"),
            acceptClick(1_050L),
            pickupObs(1_200L, "Bill Miller BBQ", TaskSubFlow.NAVIGATION),
            pickupObs(1_300L, "Bill Miller BBQ", TaskSubFlow.ARRIVED),
        )
        val pickup = r.activeTask
        assertNotNull("pickup task active", pickup)
        assertEquals(TaskPhase.PICKUP, pickup!!.phase)
        assertNotNull("pickup arrived", pickup.arrivedAt)
        val pickupId = pickup.taskId

        // Two coarse task:active frames interleaved — each must be fully inert.
        var prev: Flow? = Flow.TaskPickupArrived
        repeat(2) { i ->
            val (next, nextPrev) = step(r, prev, activeTripObs(1_400L + i * 100L))
            r = next; prev = nextPrev
            val t = r.activeTask
            assertNotNull("active task still present after task:active #$i", t)
            assertEquals("same task id (no re-mint/displace) #$i", pickupId, t!!.taskId)
            assertEquals("still PICKUP phase #$i", TaskPhase.PICKUP, t.phase)
            assertNotNull("arrivedAt intact #$i", t.arrivedAt)
            assertNull("not completed #$i", t.completedAt)
            assertNull("no retire/destructive armed #$i", r.pendingDestructive)
            assertTrue("no task moved to recentTasks #$i", r.recentTasks.isEmpty())
        }

        // The real dropoff frame still displaces the pickup and activates the drop.
        val (afterDrop, _) = step(r, prev, dropoffNav(1_700L, "cx-1"))
        assertEquals("dropoff now active", TaskPhase.DROPOFF, afterDrop.activeTask?.phase)
        val completedPickup = afterDrop.recentTasks.firstOrNull { it.taskId == pickupId }
        assertNotNull("pickup displaced to recentTasks by the real dropoff frame", completedPickup)
        assertNotNull("pickup completed by the real transition (not by task:active)", completedPickup!!.completedAt)
    }

    // =====================================================================
    // (c) MODE — task:active resolves Online; Paused behavior (#605 grace)
    // =====================================================================

    @Test
    fun `resolveMode maps task-active to Online`() {
        assertEquals(Mode.Online, policy.resolveMode(Flow.TaskActive, null))
        assertEquals(Mode.Online, policy.resolveMode(Flow.TaskActive, Mode.Online))
    }

    @Test
    fun `a task-active screen while Paused arms the graced resume, then commits Online`() {
        // Documented current semantics (#605): a task-flow SCREEN while Paused is treated like any
        // non-offer online-implying screen — it ARMS the pause-resume grace and STAYS Paused this
        // frame (only OfferPresented commits Paused->Online instantly). A sustained/lazy-expiry
        // commits the resume. (An immediate resume-on-task-flow would be a separate #605 change; not
        // widened here.)
        val paused = region(Platform.Uber).copy(mode = Mode.Paused)
        val (armed, _) = step(paused, Flow.Idle, activeTripObs(2_000L))
        assertEquals("stays Paused this frame", Mode.Paused, armed.mode)
        assertNotNull("pause-resume grace armed", armed.pendingModeResume)
        assertEquals(
            "grace deadline = ts + pauseResumeGraceMs",
            2_000L + policy.pauseResumeGraceMs(Platform.Uber),
            armed.pendingModeResume!!.deadline,
        )

        // A GRACE_COMMIT-style non-flow observation past the deadline commits the resume.
        val committed = stepper.step(
            armed, FlowRegion(flow = Flow.TaskActive), FlowRegion(flow = Flow.TaskActive),
            Observation.Timeout(timestamp = armed.pendingModeResume!!.deadline + 1L, type = TimeoutType.MODE_RESUME_COMMIT),
            policy,
        )
        assertEquals("resume commits Online past the grace", Mode.Online, committed.mode)
    }

    // =====================================================================
    // (d) ACCEPT-GRACE is per-platform (DoorDash 120s, Uber 600s)
    // =====================================================================

    @Test
    fun `DoorDash accept expires past 120s — no economics consumed`() {
        val acceptAt = 1_050L
        val r = drive(
            region(Platform.DoorDash),
            offerObs(1_000L, "o1", "Bill Miller BBQ", pay = 16.0),
            acceptClick(acceptAt),
            activeTripObs(acceptAt + 150_000L), // > 120s DoorDash grace
        )
        assertNotNull("a bare job still forms on the task frame", r.activeJob)
        assertTrue("but the expired accept folds NO economics", r.activeJob!!.acceptedOffers.isEmpty())
    }

    @Test
    fun `Uber accept survives at 300s (its grace is 600s) — economics consumed`() {
        val acceptAt = 1_050L
        val r = drive(
            region(Platform.Uber),
            offerObs(1_000L, "o1", "Bill Miller BBQ", pay = 16.0),
            acceptClick(acceptAt),
            activeTripObs(acceptAt + 300_000L), // > 120s but < Uber's 600s grace
        )
        assertTrue("Uber's wider grace keeps the accept consumable", r.activeJob!!.acceptedOffers.isNotEmpty())
        assertEquals("gross pay recovered", 16.0, r.activeJob!!.totalPayAmount, 0.001)
    }

    @Test
    fun `Uber accept still expires past its own 600s grace`() {
        val acceptAt = 1_050L
        val r = drive(
            region(Platform.Uber),
            offerObs(1_000L, "o1", "Bill Miller BBQ", pay = 16.0),
            acceptClick(acceptAt),
            activeTripObs(acceptAt + 700_000L), // > Uber's 600s grace
        )
        assertTrue("past 600s the Uber accept expires too", r.activeJob!!.acceptedOffers.isEmpty())
    }
}
