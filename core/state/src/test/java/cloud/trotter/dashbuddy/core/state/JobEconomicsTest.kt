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
}
