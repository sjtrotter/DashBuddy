package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #438 item 5 (D3) — **per-region lifecycle edges**.
 *
 * The R0 [FlowRegion] is a single global slot, but its flow drove every platform's lifecycle edges
 * (accept, PostTask entry/exit). Under concurrency the global flow is whatever platform last touched
 * the screen, so a foreign frame fired THIS platform's edges — a job minted from the other platform's
 * offer, a premature completion from a non-observing region. B1 stamps [PlatformRegion.lastActedFlow]
 * (the last non-null flow THIS region stepped) and diffs every lifecycle edge against it, in both the
 * stepper and [EffectMap]. Single-platform behavior is identical (the region acts on every own frame,
 * so its lastActedFlow tracks R0.flow, and a null falls back to the global flow).
 */
class PerRegionLifecycleEdgesTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()

    private val machine = StateMachine(
        flowStepper = FlowRegionStepper(),
        platformStepper = PlatformRegionStepper(),
        crossPlatformStepper = CrossPlatformRegionStepper(),
        transitionPolicy = TransitionPolicy(),
        effectMap = EffectMap(),
    )

    // ---- helpers ----

    private fun order(i: Int, store: String) = ParsedOrder(
        orderIndex = i, orderType = OrderType.PICKUP, storeName = store,
        itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
    )

    /** A shared-R0 flow holding a DoorDash accept-latched offer (as if DoorDash just presented it). */
    private fun doordashOfferFlow(hash: String = "dd-offer") = FlowRegion(
        flow = Flow.OfferPresented,
        pendingOffer = PendingOffer(
            offerHash = hash,
            offerFields = ParsedFields.OfferFields(
                parsedOffer = ParsedOffer(
                    offerHash = hash, payAmount = 14.0, distanceMiles = 5.0,
                    timeToCompleteMinutes = 20L, orders = listOf(order(0, "Bill Miller BBQ")),
                ),
            ),
            presentedAt = 500L,
            returnFlow = Flow.Idle,
            lastClickIntent = OfferIntent.ACCEPT,
            sourceRuleId = "doordash.screen.offer",
        ),
        sourceRuleId = "doordash.screen.offer",
        activePlatform = Platform.DoorDash,
    )

    private fun uberPickupNav(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "uber.screen.pickup_nav",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(
            phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = "Uber Diner",
        ),
    )

    private fun uberIdle(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "uber.screen.idle",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    // =====================================================================
    // (a) NO CROSS-PLATFORM JOB MINT / ECONOMICS
    // =====================================================================

    @Test
    fun `an Uber task frame does not mint a job from a DoorDash offer in the shared R0`() {
        // The Uber region is online and has already acted on its own idle frame — the realistic
        // concurrent case. A DoorDash accept-latched offer sits in the shared R0.
        val uber = PlatformRegion(
            platform = Platform.Uber, mode = Mode.Online,
            session = Session("u-sess", startedAt = 100L),
            lastActedFlow = Flow.Idle,
        )
        val ddOfferFlow = doordashOfferFlow()
        // R0 becomes an Uber pickup nav (the frame pops the offer, activePlatform → Uber).
        val nextFlow = FlowRegion(flow = Flow.TaskPickupNavigation, activePlatform = Platform.Uber)

        val result = stepper.step(uber, ddOfferFlow, nextFlow, uberPickupNav(1_000L), policy)

        // The Uber region mints its OWN bare job (a genuine Uber task) — never the DoorDash offer's.
        assertTrue(
            "no cross-platform economics: the Uber job carries no accepted offer",
            result.activeJob?.acceptedOffers.isNullOrEmpty(),
        )
        assertNotEquals(
            "the Uber job is NOT parented to the DoorDash offer",
            "dd-offer", result.activeJob?.parentOfferHash,
        )
        // Uber acted on its own pickup flow.
        assertEquals(Flow.TaskPickupNavigation, result.lastActedFlow)
    }

    // =====================================================================
    // (b) NO PREMATURE COMPLETION FROM A NON-OBSERVING REGION
    // =====================================================================

    @Test
    fun `a DoorDash region on PostTask does not complete when an Uber frame moves the global flow`() {
        val dropoff = Task(
            taskId = "dd-drop", jobId = "dd-job", phase = TaskPhase.DROPOFF,
            storeName = "HEB", customerNameHash = "cx-1", startedAt = 300L, arrivedAt = 400L,
        )
        val ddRegion = PlatformRegion(
            platform = Platform.DoorDash, mode = Mode.Online,
            session = Session("dd-sess", startedAt = 100L),
            activeJob = Job("dd-job", offerStoreHint = listOf("HEB"), parentOfferHash = "o1", startedAt = 200L, tasks = listOf(dropoff)),
            activeTask = dropoff,
            lastPostTaskFields = ParsedFields.PostTaskFields(totalPay = 12.0),
            lastAnnouncedPostTaskTaskId = "dd-drop",
            lastActedFlow = Flow.PostTask,
        )
        val uberRegion = PlatformRegion(
            platform = Platform.Uber, mode = Mode.Online,
            session = Session("u-sess", startedAt = 100L),
            lastActedFlow = Flow.Idle,
        )
        // The global R0 currently shows PostTask (DoorDash owns it); the incoming Uber frame moves it.
        val prev = AppState(
            regions = Regions(
                flow = FlowRegion(flow = Flow.PostTask, activePlatform = Platform.DoorDash),
                platforms = mapOf(Platform.DoorDash to ddRegion, Platform.Uber to uberRegion),
            ),
            timestamp = 1_000L,
        )

        val transition = machine.step(prev, uberIdle(1_200L))

        val completed = transition.effects
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
        assertTrue(
            "the DoorDash delivery must NOT complete off an Uber frame (it never left its own PostTask)",
            completed.isEmpty(),
        )
        // The DoorDash region is untouched — its PostTask is intact for its own next frame.
        assertEquals(Flow.PostTask, transition.newState.regions.platforms.getValue(Platform.DoorDash).lastActedFlow)
    }

    @Test
    fun `a DoorDash PostTask receipt fires exactly one Saved bubble - none from the non-observing Uber region`() {
        val ddDrop = Task(
            taskId = "dd-drop", jobId = "dd-job", phase = TaskPhase.DROPOFF,
            storeName = "HEB", customerNameHash = "cx-1", startedAt = 300L, arrivedAt = 400L,
        )
        val ddRegion = PlatformRegion(
            platform = Platform.DoorDash, mode = Mode.Online,
            session = Session("dd-sess", startedAt = 100L),
            activeJob = Job("dd-job", offerStoreHint = listOf("HEB"), parentOfferHash = "o1", startedAt = 200L, tasks = listOf(ddDrop)),
            activeTask = ddDrop,
            lastActedFlow = Flow.TaskDropoffArrived,
        )
        // The Uber region is mid-pickup with its own active task — the non-observing region.
        val uPick = Task(taskId = "u-pick", jobId = "u-job", phase = TaskPhase.PICKUP, storeName = "Uber Diner", startedAt = 300L)
        val uberRegion = PlatformRegion(
            platform = Platform.Uber, mode = Mode.Online,
            session = Session("u-sess", startedAt = 100L),
            activeJob = Job("u-job", offerStoreHint = listOf("Uber Diner"), parentOfferHash = null, startedAt = 200L),
            activeTask = uPick,
            lastActedFlow = Flow.TaskPickupNavigation,
        )
        val prev = AppState(
            regions = Regions(
                flow = FlowRegion(flow = Flow.TaskDropoffArrived, activePlatform = Platform.DoorDash),
                platforms = mapOf(Platform.DoorDash to ddRegion, Platform.Uber to uberRegion),
            ),
            timestamp = 1_000L,
        )
        // A DoorDash PostTask receipt frame (its own).
        val ddPostTask = Observation.Screen(
            timestamp = 1_500L, captureId = null, ruleId = "doordash.screen.post_task",
            metadata = ReplayMetadata.EMPTY, flow = Flow.PostTask, modeHint = Mode.Online,
            parsed = ParsedFields.PostTaskFields(totalPay = 12.0),
        )

        val transition = machine.step(prev, ddPostTask)

        val savedBubbles = transition.effects
            .filterIsInstance<AppEffect.UpdateBubble>()
            .filter { it.text.startsWith("Saved:") }
        assertEquals(
            "exactly one receipt bubble — the non-observing Uber region must not fire one off DoorDash's receipt",
            1, savedBubbles.size,
        )
    }

    // =====================================================================
    // (c) A FLOW-LESS OWN-PLATFORM OBSERVATION NEITHER STAMPS NOR FIRES EDGES
    // =====================================================================

    @Test
    fun `a flow-less own-platform notification does not stamp lastActedFlow`() {
        val dropoff = Task(
            taskId = "dd-drop", jobId = "dd-job", phase = TaskPhase.DROPOFF,
            storeName = "HEB", customerNameHash = "cx-1", startedAt = 300L, arrivedAt = 400L,
        )
        val region = PlatformRegion(
            platform = Platform.DoorDash, mode = Mode.Online,
            session = Session("dd-sess", startedAt = 100L),
            activeJob = Job("dd-job", offerStoreHint = listOf("HEB"), parentOfferHash = "o1", startedAt = 200L),
            activeTask = dropoff,
            lastActedFlow = Flow.TaskDropoffNavigation,
        )
        // A flow-less DoorDash notification (flow = null) — not this platform acting on a screen.
        val notif = Observation.Notification(
            timestamp = 2_000L, captureId = null, ruleId = "doordash.notif.order_update",
            metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null, parsed = ParsedFields.None,
        )

        val result = stepper.step(region, FlowRegion(flow = Flow.Idle), FlowRegion(flow = Flow.Idle), notif, policy)

        assertEquals(
            "a flow-less obs leaves lastActedFlow unchanged (never imports the global flow)",
            Flow.TaskDropoffNavigation, result.lastActedFlow,
        )
        // No lifecycle edge fired: the active task/job survive untouched (next == prev, not a flow edge).
        assertEquals("dd-drop", result.activeTask?.taskId)
        assertEquals("dd-job", result.activeJob?.jobId)
        assertNull("no destructive edge armed by a flow-less obs", result.pendingDestructive)
    }

    // =====================================================================
    // STAMPING — a flow-bearing own observation records lastActedFlow
    // =====================================================================

    @Test
    fun `a flow-bearing observation stamps lastActedFlow`() {
        val region = PlatformRegion(
            platform = Platform.DoorDash, mode = Mode.Online,
            session = Session("dd-sess", startedAt = 100L),
        )
        val result = stepper.step(
            region, FlowRegion(flow = Flow.Idle), FlowRegion(flow = Flow.Idle), uberIdle(1_000L).copy(
                ruleId = "doordash.screen.idle",
            ),
            policy,
        )
        assertEquals(Flow.Idle, result.lastActedFlow)
    }
}
