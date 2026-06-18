package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
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
import org.junit.Test
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality

/**
 * Accepted-offer economics on the [Job] (issue #317). A first accept seeds the job's
 * economics; an **add-on** offer accepted while a job is already active accumulates onto the
 * same job (so the Task card's per-job blended $/hr is correct for stacked orders), and a
 * re-entered OfferPresented for the same offer must not double-count.
 */
class JobEconomicsTest {

    private val stepper = PlatformRegionStepper()
    private val flowStepper = FlowRegionStepper()
    private val policy = TransitionPolicy()

    // ---- helpers ----

    private fun onlineRegion(activeJob: Job?) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
        activeJob = activeJob,
    )

    private fun eval(net: Double, est: Double, dist: Double, pay: Double) = OfferEvaluation(
        action = OfferAction.ACCEPT,
        score = 75.0,
        qualityLevel = OfferQuality.GOOD,
        payAmount = pay,
        fuelCostEstimate = 0.0,
        netPayAmount = net,
        distanceMiles = dist,
        dollarsPerMile = if (dist > 0) net / dist else 0.0,
        dollarsPerHour = if (est > 0) net / (est / 60.0) else 0.0,
        estimatedTimeMinutes = est,
        itemCount = 1.0,
        merchantName = "Test Store",
    )

    private fun offerFlow(offerHash: String, net: Double, est: Double, dist: Double, pay: Double) = FlowRegion(
        flow = Flow.OfferPresented,
        pendingOffer = PendingOffer(
            offerHash = offerHash,
            offerFields = ParsedFields.OfferFields(
                parsedOffer = ParsedOffer(
                    offerHash = offerHash,
                    payAmount = pay,
                    distanceMiles = dist,
                    timeToCompleteMinutes = est.toLong(),
                ),
            ),
            presentedAt = 500L,
            evaluation = eval(net = net, est = est, dist = dist, pay = pay),
            returnFlow = Flow.Idle,
        ),
    )

    private fun acceptObs(timestamp: Long, store: String) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "doordash.screen.pickup_nav",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(
            phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = store,
        ),
    )

    private fun dropoffObs(timestamp: Long, customerNameHash: String?, customerAddressHash: String? = null) =
        Observation.Screen(
            timestamp = timestamp, captureId = null, ruleId = "doordash.screen.dropoff_nav",
            metadata = ReplayMetadata.EMPTY, flow = Flow.TaskDropoffNavigation, modeHint = Mode.Online,
            parsed = ParsedFields.TaskFields(
                phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION,
                customerNameHash = customerNameHash, customerAddressHash = customerAddressHash,
            ),
        )

    private fun jobWith(offerHash: String, net: Double, est: Double, dist: Double, pay: Double) = Job(
        jobId = "job-1",
        offerStoreHint = listOf("H-E-B"),
        parentOfferHash = offerHash,
        acceptedOffers = listOf(
            AcceptedOfferEconomics(
                offerHash = offerHash, payAmount = pay, netPay = net,
                estMinutes = est, distanceMiles = dist, acceptedAt = 200L,
            ),
        ),
        startedAt = 200L,
    )

    private fun step(prev: PlatformRegion, prevFlow: FlowRegion, obs: Observation): PlatformRegion {
        val nextFlow = if (obs is Observation.FlowObservation) flowStepper.step(prevFlow, obs) else prevFlow
        return stepper.step(prev, prevFlow, nextFlow, obs, policy)
    }

    // ---- tests ----

    @Test
    fun `accept pre-creates the offer's dropoff subtask, customer TBD (#503 slice 3)`() {
        val r1 = step(
            onlineRegion(activeJob = null),
            offerFlow("offer-1", net = 6.0, est = 18.0, dist = 4.0, pay = 8.5),
            acceptObs(1_000L, "H-E-B"),
        )
        val job = r1.activeJob
        assertNotNull("accept starts a job", job)
        val dropoffs = job!!.tasks.filter { it.phase == TaskPhase.DROPOFF }
        assertEquals("accept pre-creates exactly one dropoff subtask", 1, dropoffs.size)
        assertNull("its customer is TBD until the dropoff screen resolves it", dropoffs.single().customerNameHash)
        assertEquals("the active task is the pickup, not the pre-created dropoff", TaskPhase.PICKUP, r1.activeTask?.phase)
    }

    @Test
    fun `the dropoff screen resolves the customer onto the pre-created subtask, no fresh mint (#503 slice 3)`() {
        val r1 = step(
            onlineRegion(activeJob = null),
            offerFlow("offer-1", net = 6.0, est = 18.0, dist = 4.0, pay = 8.5),
            acceptObs(1_000L, "H-E-B"),
        )
        val expectedDropoffId = r1.activeJob!!.tasks.single { it.phase == TaskPhase.DROPOFF }.taskId

        // The dropoff screen (a real customer) RESOLVES onto the pre-created subtask — same taskId,
        // customer filled in — instead of minting a fresh dropoff.
        val r2 = step(r1, FlowRegion(flow = Flow.TaskPickupNavigation), dropoffObs(2_000L, customerNameHash = "cust-1"))

        assertEquals("resolves onto the pre-created subtask (same taskId)", expectedDropoffId, r2.activeTask?.taskId)
        assertEquals("the customer is now resolved", "cust-1", r2.activeTask?.customerNameHash)
        assertEquals(
            "no phantom: the job still has exactly the pickup + the (now-resolved) dropoff",
            2,
            r2.activeJob?.tasks?.size,
        )
        assertEquals(
            "exactly one DROPOFF subtask (the resolved one), no duplicate",
            1,
            r2.activeJob?.tasks?.count { it.phase == TaskPhase.DROPOFF },
        )
    }

    @Test
    fun `first accept seeds job economics from the offer`() {
        val r1 = step(
            onlineRegion(activeJob = null),
            offerFlow("offer-1", net = 6.0, est = 18.0, dist = 4.0, pay = 8.5),
            acceptObs(1_000L, "H-E-B"),
        )

        val job = r1.activeJob
        assertNotNull("offer accept must start a job", job)
        assertEquals(1, job!!.acceptedOffers.size)
        assertEquals(6.0, job.totalNetPay, 0.001)
        assertEquals(18.0, job.totalEstMinutes, 0.001)
        assertEquals(8.5, job.totalPayAmount, 0.001)
        assertEquals(listOf("offer-1"), job.parentOfferHashes)
    }

    @Test
    fun `add-on offer accepted mid-job accumulates onto the active job`() {
        val r1 = step(
            onlineRegion(activeJob = jobWith("offer-1", net = 6.0, est = 18.0, dist = 4.0, pay = 8.5)),
            offerFlow("offer-2", net = 5.0, est = 12.0, dist = 3.0, pay = 7.0),
            acceptObs(2_000L, "Chipotle"),
        )

        val job = r1.activeJob
        assertNotNull(job)
        assertEquals("add-on stays on the same job, not a new one", "job-1", job!!.jobId)
        assertEquals(2, job.acceptedOffers.size)
        assertEquals("net pay blends across the stacked offers", 11.0, job.totalNetPay, 0.001)
        assertEquals("est minutes sum across the stacked offers", 30.0, job.totalEstMinutes, 0.001)
        assertEquals(15.5, job.totalPayAmount, 0.001)
        assertEquals(listOf("offer-1", "offer-2"), job.parentOfferHashes)
    }

    @Test
    fun `re-accepting the same offer does not double-count`() {
        val r1 = step(
            onlineRegion(activeJob = jobWith("offer-1", net = 6.0, est = 18.0, dist = 4.0, pay = 8.5)),
            offerFlow("offer-1", net = 6.0, est = 18.0, dist = 4.0, pay = 8.5),
            acceptObs(2_000L, "H-E-B"),
        )

        val job = r1.activeJob
        assertNotNull(job)
        assertEquals("offer-1 already counted → no duplicate", 1, job!!.acceptedOffers.size)
        assertEquals(6.0, job.totalNetPay, 0.001)
    }

    @Test
    fun `blended economics are null with no accepted offers, summed otherwise (#460)`() {
        // The task card's $/hr co-hero shows "—" (null), never a misleading $0,
        // when no accepted-offer economics exist yet.
        val empty = Job(jobId = "j0", offerStoreHint = emptyList(), parentOfferHash = "o0", startedAt = 0L)
        assertNull(empty.blendedNetPay)
        assertNull(empty.blendedEstMinutes)
        assertNull("no distance → null, never a misleading per-mile (#503 live per-mile)", empty.blendedDistanceMiles)

        val seeded = jobWith("o1", net = 6.5, est = 18.0, dist = 4.2, pay = 7.5)
        assertEquals(6.5, seeded.blendedNetPay!!, 0.001)
        assertEquals(18.0, seeded.blendedEstMinutes!!, 0.001)
        assertEquals("blended distance sums the accepted offers (#503 live per-mile)", 4.2, seeded.blendedDistanceMiles!!, 0.001)
    }
}
