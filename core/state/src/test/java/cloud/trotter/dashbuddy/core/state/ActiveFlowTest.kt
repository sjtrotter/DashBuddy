package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
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
            // Finding 5a: WHOLE-task equality (incl. subPhase) — a regression where
            // toTaskPhase(TaskActive) starts returning a phase would mutate the task here.
            assertEquals("task byte-identical to pre-interleave (incl. subPhase) #$i", pickup, t)
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
    // (b2) AMBIENT-SCREEN ACCEPT GUARD (adversarial finding 1)
    // =====================================================================

    @Test
    fun `a mid-trip offer with no clicks is NOT stamped accepted when the ambient task-active screen re-renders`() {
        // The phantom-accept red probe: driver mid-trip on a coarse platform (job already minted,
        // ambient screen = task:active), a stacked offer appears (returnFlow = task:active), the
        // dasher declines it / lets it time out (Uber has NO decline click rule → no latch), and the
        // ambient on_job_view re-renders. Pre-fix, resolveOnLeave's unconditional leavingToTask
        // stamped it accepted → phantom OFFER_ACCEPTED + phantom add-on economics.
        val r = drive(
            region(Platform.Uber),
            offerObs(1_000L, "o1", "Bill Miller BBQ", pay = 16.0),
            acceptClick(1_050L),
            activeTripObs(1_200L),                          // job minted; ambient flow = task:active
            offerObs(2_000L, "o2", "Mama Margies", pay = 9.0), // stacked offer over the ambient screen
            activeTripObs(2_500L),                          // overlay vanished — NO clicks captured
        )
        val job = r.activeJob
        assertNotNull(job)
        assertEquals("only the first offer's economics — no phantom add-on", 1, job!!.acceptedOffers.size)
        assertEquals("gross unchanged", 16.0, job.totalPayAmount, 0.001)
        assertNull("no survivor minted from the un-clicked offer", r.pendingOffers.firstOrNull { it.acceptedAt != null })
        assertTrue("the un-clicked offer resolved away (timeout/decline)", r.pendingOffers.isEmpty())
    }

    @Test
    fun `an offer presented over Idle is click-lessly accepted by a following task-active frame (positive control)`() {
        // The legitimate click-less accept this flow exists for: a job appearing where there was
        // none. Fresh region → returnFlow = Idle → the task:active destination still infers accept.
        val r = drive(
            region(Platform.Uber),
            offerObs(1_000L, "o1", "Bill Miller BBQ", pay = 12.0), // returnFlow = Idle (no prior acted flow)
            activeTripObs(1_500L),                                 // no accept click captured
        )
        val job = r.activeJob
        assertNotNull("job minted from the click-less accept", job)
        assertEquals("economics consumed", 1, job!!.acceptedOffers.size)
        assertEquals(12.0, job.totalPayAmount, 0.001)
    }

    @Test
    fun `OFFER_EXPIRY still resolves an un-clicked mid-trip offer (no frame after the overlay vanishes)`() {
        val midTrip = drive(
            region(Platform.Uber),
            offerObs(1_000L, "o1", "Bill Miller BBQ", pay = 16.0),
            acceptClick(1_050L),
            activeTripObs(1_200L),
            offerObs(2_000L, "o2", "Mama Margies", pay = 9.0),
        )
        // The overlay vanishes without a frame; the hash-carrying safety timer fires. Because the
        // guard never stamped acceptedAt, expireOffer's accepted/latched no-op does not trip.
        val expired = stepper.step(
            midTrip, FlowRegion(flow = Flow.OfferPresented), FlowRegion(flow = Flow.OfferPresented),
            Observation.Timeout(
                timestamp = 130_000L, type = TimeoutType.OFFER_EXPIRY,
                payload = ObservationPayload.OfferExpiry("o2"),
            ),
            policy,
        )
        assertTrue("the timer resolves the presented offer", expired.pendingOffers.none { it.offerHash == "o2" })
        assertNull("no survivor", expired.pendingOffers.firstOrNull { it.acceptedAt != null })
        assertEquals("job economics untouched", 1, expired.activeJob!!.acceptedOffers.size)
    }

    // =====================================================================
    // (b3) PENDING RETIRE × task:active (adversarial finding 5b)
    // =====================================================================

    @Test
    fun `a pending TASK_RETIRE survives a task-active frame - neither cancelled nor committed early`() {
        // Arm the idle-flash retire grace on a live pickup...
        val onPickup = drive(
            region(Platform.Uber),
            offerObs(1_000L, "o1", "Bill Miller BBQ"),
            acceptClick(1_050L),
            pickupObs(1_200L, "Bill Miller BBQ", TaskSubFlow.NAVIGATION),
        )
        val idle = Observation.Screen(
            timestamp = 2_000L, captureId = null, ruleId = "idle",
            metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
            parsed = ParsedFields.None,
        )
        val armed = stepper.step(
            onPickup, FlowRegion(flow = Flow.TaskPickupNavigation, activePlatform = Platform.Uber),
            FlowRegion(flow = Flow.Idle, activePlatform = Platform.Uber), idle, policy,
        )
        val pend = armed.pendingDestructive
        assertEquals("retire grace armed by the idle flash", DestructiveKind.TASK_RETIRE, pend?.kind)

        // ...then a task:active frame INSIDE the grace window: the pending must ride through
        // untouched — not cancelled (the null-phase early-return precedes the same-phase grace
        // clear) and not committed early (deadline not passed).
        val after = stepper.step(
            armed, FlowRegion(flow = Flow.Idle, activePlatform = Platform.Uber),
            FlowRegion(flow = Flow.TaskActive, activePlatform = Platform.Uber),
            activeTripObs(2_500L), policy,
        )
        assertEquals("pending retire unchanged — neither cancelled nor re-armed", pend, after.pendingDestructive)
        assertNotNull("task not retired early", after.activeTask)
        assertNull("no completedAt stamped", after.activeTask!!.completedAt)
        assertTrue("nothing moved to recentTasks", after.recentTasks.isEmpty())
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
