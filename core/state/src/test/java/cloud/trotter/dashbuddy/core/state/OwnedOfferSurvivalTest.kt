package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
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
 * #438 B3 — the **accepted-offer survival** on the platform-owned [PlatformRegion.pendingOffers]
 * (rewritten from the pre-B3 `AcceptStashTest`; the #526 accept stash it exercised is deleted).
 *
 * The job is normally minted on the `OfferPresented → task` edge, consuming THIS region's own
 * accept-latched offer. The F3 race (verified in the 07-05 two-pickup capture): a
 * `waiting_for_offer` teardown frame lands between the accept click and the first task frame,
 * popping presentation. Under B3 the accept-latched offer SURVIVES that edge as an
 * accepted-pending-consumption entry ([PendingOffer.acceptedAt] set) and the task frame still
 * mints the FULL job (economics + placeholders) — the same observable behavior the stash gave,
 * now structurally per-region.
 */
class OwnedOfferSurvivalTest {

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

    private fun offerObs(t: Long, hash: String, stores: List<String>, pay: Double = 12.0) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.offer",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(
                offerHash = hash, payAmount = pay, distanceMiles = 5.0, timeToCompleteMinutes = 20L,
                orders = stores.mapIndexed { i, s -> order(i, s) },
            ),
        ),
    )

    private fun acceptClick(t: Long) = Observation.Click(
        timestamp = t, captureId = null, ruleId = "doordash.click.accept_offer",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
    )

    private fun declineClick(t: Long) = Observation.Click(
        timestamp = t, captureId = null, ruleId = "doordash.click.decline_offer",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = OfferIntent.DECLINE),
    )

    private fun teardownObs(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.waiting_for_offer",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    private fun pickupNav(t: Long, store: String) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.pickup_nav",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = store),
    )

    private fun dropoffNav(t: Long, cx: String) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.dropoff_nav",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskDropoffNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION, customerNameHash = cx),
    )

    /** Drive a sequence through the stepper, deriving prev/next flow from each obs (as StateMachine does). */
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

    private fun PlatformRegion.survivor() = pendingOffers.firstOrNull { it.acceptedAt != null }

    // =====================================================================
    // ARMING (accept-latched offer becomes a survivor when presentation leaves)
    // =====================================================================

    @Test
    fun `an accept-latched offer survives the leave-of-presentation as a consumable entry`() {
        val r = drive(
            region(),
            offerObs(1_000L, "o1", listOf("Bill Miller BBQ", "Mama Margies")),
            acceptClick(1_050L),
            teardownObs(1_500L),
        )
        val survivor = r.survivor()
        assertNotNull("the accept-latched offer survives as accepted-pending-consumption", survivor)
        assertEquals("o1", survivor!!.offerHash)
        assertEquals("acceptedAt = the honest accept-click time", 1_050L, survivor.acceptedAt)
    }

    @Test
    fun `an un-accepted offer leaving presentation is resolved away (no survivor)`() {
        val r = drive(
            region(),
            offerObs(1_000L, "o1", listOf("Bill Miller BBQ")),
            teardownObs(1_500L), // timed out — never clicked accept
        )
        assertNull("no accept → no survivor", r.survivor())
        assertTrue("no presented offer either", r.pendingOffers.isEmpty())
    }

    // =====================================================================
    // F3 CONSUMPTION — the teardown race
    // =====================================================================

    @Test
    fun `F3 shape - accept then teardown then pickup mints a FULL job with economics and placeholders`() {
        val r = drive(
            region(),
            offerObs(1_000L, "o1", listOf("Bill Miller BBQ", "Mama Margies"), pay = 15.0),
            acceptClick(1_050L),
            teardownObs(1_500L),                 // pops presentation BEFORE the first task frame
            pickupNav(2_000L, "Bill Miller BBQ"), // the survivor mints the job here
        )
        val job = r.activeJob
        assertNotNull("pickup frame mints the job from the survivor", job)
        assertTrue("F3 fix: economics recovered", job!!.acceptedOffers.isNotEmpty())
        assertEquals("gross pay recovered", 15.0, job.totalPayAmount, 0.001)
        assertEquals("two dropoff placeholders (one per order)", 2, job.tasks.count { it.phase == TaskPhase.DROPOFF })
        assertEquals("store hints recovered", listOf("Bill Miller BBQ", "Mama Margies"), job.offerStoreHint)
        assertNull("the survivor is consumed", r.survivor())
    }

    @Test
    fun `happy path - accept then pickup (no teardown) mints the FULL job in one edge`() {
        val r = drive(
            region(),
            offerObs(1_000L, "o1", listOf("Bill Miller BBQ"), pay = 9.0),
            acceptClick(1_050L),
            pickupNav(1_200L, "Bill Miller BBQ"),
        )
        val job = r.activeJob
        assertNotNull(job)
        assertEquals("gross pay recovered", 9.0, job!!.totalPayAmount, 0.001)
        assertEquals("acceptedAt = the accept-click time", 1_050L, job.acceptedOffers.single().acceptedAt)
        assertNull("no lingering survivor", r.survivor())
        assertTrue("no presented offer left", r.pendingOffers.none { it.acceptedAt == null })
    }

    @Test
    fun `no accept - the bare fallback still mints a job with no economics`() {
        val r = drive(region(), pickupNav(2_000L, "Bill Miller BBQ"))
        val job = r.activeJob
        assertNotNull(job)
        assertTrue("bare fallback carries no accepted-offer economics", job!!.acceptedOffers.isEmpty())
    }

    @Test
    fun `an expired survivor is NOT consumed - falls to the bare fallback`() {
        val r = drive(
            region(),
            offerObs(1_000L, "o1", listOf("Bill Miller BBQ"), pay = 15.0),
            acceptClick(1_050L),
            teardownObs(1_500L),
            pickupNav(1_050L + 130_000L, "Bill Miller BBQ"), // > 120s accept grace past the click
        )
        assertTrue("expired survivor → bare fallback, no economics", r.activeJob!!.acceptedOffers.isEmpty())
        assertNull("expired survivor cleared", r.survivor())
    }

    // =====================================================================
    // SUPERSESSION
    // =====================================================================

    @Test
    fun `a newer offer supersedes an earlier accepted survivor`() {
        val r = drive(
            region(),
            offerObs(1_000L, "o1", listOf("Bill Miller BBQ")),
            acceptClick(1_050L),
            teardownObs(1_400L),                 // o1 becomes a survivor
            offerObs(1_500L, "o2", listOf("Mama Margies")), // a different offer supersedes it
        )
        assertNull(
            "the stale survivor is superseded",
            r.pendingOffers.firstOrNull { it.acceptedAt != null && it.offerHash == "o1" },
        )
        assertEquals("the new presented offer is o2", "o2", r.presentedOffer()?.offerHash)
    }

    // =====================================================================
    // ADD-ON via the teardown race
    // =====================================================================

    @Test
    fun `an add-on accepted through the teardown race folds into the active job`() {
        val existing = Job(
            jobId = "job-1", offerStoreHint = listOf("Bill Miller BBQ"), parentOfferHash = "o1",
            acceptedOffers = listOf(
                AcceptedOfferEconomics(offerHash = "o1", payAmount = 10.0, netPay = 7.0, estMinutes = 15.0, distanceMiles = 3.0, acceptedAt = 200L),
            ),
            startedAt = 200L,
        )
        val r = drive(
            region(activeJob = existing),
            offerObs(3_000L, "o2", listOf("Mama Margies"), pay = 8.0),
            acceptClick(3_050L),
            teardownObs(3_500L),
            dropoffNav(4_000L, "cx-2"),
        )
        val job = r.activeJob
        assertEquals("stays the same job", "job-1", job!!.jobId)
        assertEquals("the add-on economics folded in", 2, job.acceptedOffers.size)
        assertEquals("blended gross across both offers", 18.0, job.totalPayAmount, 0.001)
        assertNull("the survivor is consumed", r.survivor())
    }

    // =====================================================================
    // REVOCATION (decline-after-accept, #594)
    // =====================================================================

    @Test
    fun `a decline-after-accept never creates a survivor (revocation)`() {
        val r = drive(
            region(),
            offerObs(1_000L, "o1", listOf("Bill Miller BBQ")),
            acceptClick(1_050L),
            declineClick(1_200L),  // the "Review offer" → decline race committed the decline
            teardownObs(1_500L),
        )
        assertNull("revoked accept creates no survivor (no phantom add-on)", r.survivor())
        assertTrue("nothing left pending", r.pendingOffers.isEmpty())
    }

    // =====================================================================
    // SESSION END
    // =====================================================================

    @Test
    fun `the accepted survivor clears on session end`() {
        val armed = drive(
            region(),
            offerObs(1_000L, "o1", listOf("Bill Miller BBQ")),
            acceptClick(1_050L),
            teardownObs(1_400L),
        )
        assertNotNull("armed", armed.survivor())
        val ended = stepper.step(
            armed, FlowRegion(flow = Flow.Idle), FlowRegion(flow = Flow.SessionEnded),
            Observation.Screen(
                timestamp = 2_000L, captureId = null, ruleId = "doordash.screen.dash_summary",
                metadata = ReplayMetadata.EMPTY, flow = Flow.SessionEnded, modeHint = Mode.Offline,
                parsed = ParsedFields.SessionEndedFields(totalEarnings = 20.0),
            ),
            policy,
        )
        val committed = stepper.step(
            ended, FlowRegion(flow = Flow.SessionEnded), FlowRegion(flow = Flow.SessionEnded),
            Observation.Timeout(timestamp = 2_000L + 600_000L, type = cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.GRACE_COMMIT),
            policy,
        )
        assertTrue("session end wipes pending offers", committed.pendingOffers.isEmpty())
        assertNull("session ended", committed.session)
    }
}
