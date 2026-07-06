package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingOffer
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
 * #526 D1 — the **accept stash** ([PlatformRegion.lastAcceptedOffer]).
 *
 * The job is normally minted on the `OfferPresented → task` edge, reading the offer off
 * `FlowRegion.pendingOffer`. The F3 race (verified in the 07-05 two-pickup capture): a
 * `waiting_for_offer` teardown frame lands between the accept click and the first task frame,
 * popping `pendingOffer`, so that edge never fires and the job is minted by the BARE fallback —
 * no economics, no placeholders, no store hints. The stash survives the teardown: it is mirrored
 * on every step whose flow still holds an accept-latched pending offer, and consumed by whichever
 * mint path runs.
 */
class AcceptStashTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()

    private fun region(activeJob: Job? = null) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        activeJob = activeJob,
    )

    private fun order(i: Int, store: String) = ParsedOrder(
        orderIndex = i, orderType = OrderType.PICKUP, storeName = store,
        itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
    )

    /** An OfferPresented flow whose pending offer already carries an ACCEPT latch. */
    private fun acceptLatchedOffer(hash: String, stores: List<String>, pay: Double = 12.0) = FlowRegion(
        flow = Flow.OfferPresented,
        pendingOffer = PendingOffer(
            offerHash = hash,
            offerFields = ParsedFields.OfferFields(
                parsedOffer = ParsedOffer(
                    offerHash = hash,
                    payAmount = pay,
                    distanceMiles = 5.0,
                    timeToCompleteMinutes = 20L,
                    orders = stores.mapIndexed { i, s -> order(i, s) },
                ),
            ),
            presentedAt = 500L,
            returnFlow = Flow.Idle,
            lastClickIntent = OfferIntent.ACCEPT,
        ),
    )

    private val idleFlow = FlowRegion(flow = Flow.Idle)
    private val offerScreen = Observation.Screen(
        timestamp = 0L, captureId = null, ruleId = "doordash.screen.offer",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun offerObs(t: Long) = offerScreen.copy(timestamp = t)

    private fun teardownObs(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun pickupNav(t: Long, store: String) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.pickup_nav",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = store),
    )

    /** Direct stepper step — we control nextFlow so arming is deterministic. */
    private fun step(prev: PlatformRegion, prevFlow: FlowRegion, nextFlow: FlowRegion, obs: Observation) =
        stepper.step(prev, prevFlow, nextFlow, obs, policy)

    // =====================================================================
    // ARMING
    // =====================================================================

    @Test
    fun `an accept-latched pending offer arms the stash`() {
        val offer = acceptLatchedOffer("o1", listOf("Bill Miller BBQ", "Mama Margies"))
        val r = step(region(), idleFlow, offer, offerObs(1_000L))
        val stash = r.lastAcceptedOffer
        assertNotNull("the accept-latched offer arms a stash", stash)
        assertEquals("o1", stash!!.offerHash)
        assertEquals(1_000L, stash.acceptedAt)
        assertEquals(listOf("Bill Miller BBQ", "Mama Margies"), stash.storeHints)
    }

    @Test
    fun `an un-accepted pending offer does NOT arm the stash`() {
        val offer = acceptLatchedOffer("o1", listOf("Bill Miller BBQ")).let {
            it.copy(pendingOffer = it.pendingOffer!!.copy(lastClickIntent = null))
        }
        val r = step(region(), idleFlow, offer, offerObs(1_000L))
        assertNull("no accept latch → no stash", r.lastAcceptedOffer)
    }

    @Test
    fun `re-mirroring keeps the original acceptedAt (idempotent by offerHash)`() {
        val offer = acceptLatchedOffer("o1", listOf("Bill Miller BBQ"))
        val r1 = step(region(), idleFlow, offer, offerObs(1_000L))
        val r2 = step(r1, offer, offer, offerObs(1_400L))
        assertEquals("acceptedAt preserved across re-mirrors", 1_000L, r2.lastAcceptedOffer!!.acceptedAt)
    }

    // =====================================================================
    // F3 CONSUMPTION — the teardown race
    // =====================================================================

    @Test
    fun `F3 shape - accept then teardown then pickup mints a FULL job with economics and placeholders`() {
        // Arm on the accept-latched offer.
        val offer = acceptLatchedOffer("o1", listOf("Bill Miller BBQ", "Mama Margies"), pay = 15.0)
        val r1 = step(region(), idleFlow, offer, offerObs(1_000L))
        // A waiting_for_offer teardown pops the pending offer BEFORE the first task frame.
        val r2 = step(r1, offer, idleFlow, teardownObs(1_500L))
        assertNotNull("the stash survives the teardown", r2.lastAcceptedOffer)
        assertNull("no job minted yet", r2.activeJob)
        // The first task frame (pickup nav) mints the job from the stash — the accept edge never fired.
        val r3 = step(r2, idleFlow, FlowRegion(flow = Flow.TaskPickupNavigation), pickupNav(2_000L, "Bill Miller BBQ"))

        val job = r3.activeJob
        assertNotNull("pickup frame mints the job from the stash", job)
        assertTrue("F3 fix: economics recovered (master mints bare)", job!!.acceptedOffers.isNotEmpty())
        assertEquals("gross pay recovered from the stash", 15.0, job.totalPayAmount, 0.001)
        assertEquals("two dropoff placeholders (one per order)", 2, job.tasks.count { it.phase == TaskPhase.DROPOFF })
        assertEquals("store hints recovered", listOf("Bill Miller BBQ", "Mama Margies"), job.offerStoreHint)
        assertNull("the stash is consumed", r3.lastAcceptedOffer)
    }

    @Test
    fun `no stash - the bare fallback still mints a job with no economics`() {
        // A task frame with no prior accept (genuine recovery/restart) → bare fallback, unchanged.
        val r = step(region(), idleFlow, FlowRegion(flow = Flow.TaskPickupNavigation), pickupNav(2_000L, "Bill Miller BBQ"))
        val job = r.activeJob
        assertNotNull(job)
        assertTrue("bare fallback carries no accepted-offer economics", job!!.acceptedOffers.isEmpty())
    }

    @Test
    fun `an expired stash is NOT consumed - falls to the bare fallback`() {
        val offer = acceptLatchedOffer("o1", listOf("Bill Miller BBQ"), pay = 15.0)
        val r1 = step(region(), idleFlow, offer, offerObs(1_000L))
        val r2 = step(r1, offer, idleFlow, teardownObs(1_500L))
        // Pickup arrives well past the 120s accept grace.
        val r3 = step(r2, idleFlow, FlowRegion(flow = Flow.TaskPickupNavigation), pickupNav(1_000L + 130_000L, "Bill Miller BBQ"))
        assertTrue("expired stash → bare fallback, no economics", r3.activeJob!!.acceptedOffers.isEmpty())
        assertNull("expired stash is cleared", r3.lastAcceptedOffer)
    }

    // =====================================================================
    // SUPERSESSION
    // =====================================================================

    @Test
    fun `a newer offer supersedes an earlier stash`() {
        val a = acceptLatchedOffer("o1", listOf("Bill Miller BBQ"))
        val r1 = step(region(), idleFlow, a, offerObs(1_000L))
        assertEquals("o1", r1.lastAcceptedOffer!!.offerHash)
        // A different offer appears and is accepted — it supersedes the earlier stash.
        val b = acceptLatchedOffer("o2", listOf("Mama Margies"))
        val r2 = step(r1, a, b, offerObs(1_400L))
        assertEquals("the newer offer supersedes", "o2", r2.lastAcceptedOffer!!.offerHash)
    }

    // =====================================================================
    // D1c — ADD-ON via the teardown race
    // =====================================================================

    @Test
    fun `D1c - an add-on accepted through the teardown race folds into the active job`() {
        val existing = Job(
            jobId = "job-1", offerStoreHint = listOf("Bill Miller BBQ"), parentOfferHash = "o1",
            acceptedOffers = listOf(
                cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics(
                    offerHash = "o1", payAmount = 10.0, netPay = 7.0, estMinutes = 15.0,
                    distanceMiles = 3.0, acceptedAt = 200L,
                ),
            ),
            startedAt = 200L,
        )
        // Arm an add-on stash while the job is active.
        val addOn = acceptLatchedOffer("o2", listOf("Mama Margies"), pay = 8.0)
        val r1 = step(region(activeJob = existing), idleFlow, addOn, offerObs(3_000L))
        assertNotNull("add-on stash armed", r1.lastAcceptedOffer)
        // Teardown pops the offer, then the task flow resumes with the job still active.
        val r2 = step(r1, addOn, idleFlow, teardownObs(3_500L))
        val r3 = step(r2, idleFlow, FlowRegion(flow = Flow.TaskDropoffNavigation),
            Observation.Screen(
                timestamp = 4_000L, captureId = null, ruleId = "doordash.screen.dropoff_nav",
                metadata = ReplayMetadata.EMPTY, flow = Flow.TaskDropoffNavigation, modeHint = Mode.Online,
                parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION, customerNameHash = "cx-2"),
            ),
        )
        val job = r3.activeJob
        assertEquals("stays the same job", "job-1", job!!.jobId)
        assertEquals("D1c: the add-on economics folded in", 2, job.acceptedOffers.size)
        assertEquals("blended gross across both offers", 18.0, job.totalPayAmount, 0.001)
        assertNull("the stash is consumed", r3.lastAcceptedOffer)
    }

    @Test
    fun `the stash clears on session end`() {
        val offer = acceptLatchedOffer("o1", listOf("Bill Miller BBQ"))
        val r1 = step(region(), idleFlow, offer, offerObs(1_000L))
        assertNotNull(r1.lastAcceptedOffer)
        val ended = stepper.step(
            r1, offer, FlowRegion(flow = Flow.SessionEnded),
            Observation.Screen(
                timestamp = 2_000L, captureId = null, ruleId = "doordash.screen.dash_summary",
                metadata = ReplayMetadata.EMPTY, flow = Flow.SessionEnded, modeHint = Mode.Offline,
                parsed = ParsedFields.SessionEndedFields(totalEarnings = 20.0),
            ),
            policy,
        )
        // Force the grace to commit with a follow-up timeout well past the deadline.
        val committed = stepper.step(
            ended, FlowRegion(flow = Flow.SessionEnded), FlowRegion(flow = Flow.SessionEnded),
            Observation.Timeout(timestamp = 2_000L + 600_000L, type = cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.GRACE_COMMIT),
            policy,
        )
        assertNull("session end wipes the stash", committed.lastAcceptedOffer)
        assertNull("session ended", committed.session)
    }
}
