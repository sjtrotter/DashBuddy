package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.PendingDestructive
import cloud.trotter.dashbuddy.domain.state.PendingOffer
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
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
 * #596 — a *physically-complete* job closes on its next exit signal even when DoorDash skips the
 * post-delivery receipt (the pre-#596 machine's only job-exit). Covers the pure completeness
 * predicate, T1 (retire-commit closes a complete job), and T2 (an accept over a complete job starts
 * a NEW job instead of absorbing the offer), plus the amdt-4 regression (an offer-deliberation retire
 * must NOT false-close the still-undelivered final drop — that accept is an add-on).
 */
class JobCloseOutTest {

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

    private fun dropoff(id: String, jobId: String, customer: String?, completedAt: Long? = null) = Task(
        taskId = id, jobId = jobId, phase = TaskPhase.DROPOFF,
        customerNameHash = customer, startedAt = 1_000L, arrivedAt = 1_200L, completedAt = completedAt,
    )

    private fun jobWithDropoffs(jobId: String, tasks: List<Task>) = Job(
        jobId = jobId,
        offerStoreHint = emptyList(),
        parentOfferHash = "offer-0",
        acceptedOffers = listOf(
            AcceptedOfferEconomics(offerHash = "offer-0", payAmount = 8.5, netPay = 6.0, estMinutes = 18.0, distanceMiles = 4.0, acceptedAt = 200L),
        ),
        startedAt = 900L,
        tasks = tasks,
    )

    private fun eval(net: Double = 5.0, est: Double = 12.0, dist: Double = 3.0, pay: Double = 7.0) = OfferEvaluation(
        action = OfferAction.ACCEPT, score = 75.0, qualityLevel = OfferQuality.GOOD,
        payAmount = pay, fuelCostEstimate = 0.0, netPayAmount = net,
        distanceMiles = dist, dollarsPerMile = net / dist, dollarsPerHour = net / (est / 60.0),
        estimatedTimeMinutes = est, itemCount = 1.0, merchantName = "Test Store",
    )

    // #438 B3: an accept-latched offer that has left presentation — an accepted-pending-consumption
    // survivor on the region's own pendingOffers, ready for the task edge to mint.
    private fun acceptedOffer(offerHash: String) = PendingOffer(
        offerHash = offerHash,
        offerFields = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(offerHash = offerHash, payAmount = 7.0, distanceMiles = 3.0, timeToCompleteMinutes = 12L),
        ),
        presentedAt = 500L,
        evaluation = eval(),
        returnFlow = Flow.Idle,
        lastClickIntent = OfferIntent.ACCEPT,
        acceptClickAt = 500L,
        acceptedAt = 500L,
    )

    private fun acceptObs(timestamp: Long, store: String) = Observation.Screen(
        timestamp = timestamp, captureId = null, ruleId = "doordash.screen.pickup_nav",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = store),
    )

    private fun graceTimeout(timestamp: Long) = Observation.Timeout(
        timestamp = timestamp, type = TimeoutType.GRACE_COMMIT, targetPlatform = Platform.DoorDash,
    )

    // #438 B3: seed the accepted survivor on the region, then step the task frame that consumes it.
    private fun stepAccept(prev: PlatformRegion, offer: PendingOffer, obs: Observation): PlatformRegion {
        val seeded = prev.copy(pendingOffers = prev.pendingOffers + offer)
        val prevFlow = FlowRegion(flow = Flow.Idle, activePlatform = prev.platform)
        val nextFlow = if (obs is Observation.FlowObservation) flowStepper.step(prevFlow, obs) else prevFlow
        return stepper.step(seeded, prevFlow, nextFlow, obs, policy)
    }

    // ---- predicate (pure) ----

    @Test
    fun `predicate — a fully delivered job is physically complete`() {
        val d = dropoff("task-d1", "job-1", customer = "cust-1", completedAt = 3_000L)
        val job = jobWithDropoffs("job-1", tasks = listOf(d))
        assertTrue(isJobPhysicallyComplete(job, recentTasks = listOf(d), justRetired = null))
    }

    @Test
    fun `predicate — the just-retired drop counts even though the mirror is stale (amdt 6)`() {
        // At retire time Job.tasks still shows completedAt == null (the mirror re-runs after stepCore).
        val staleMirror = dropoff("task-d1", "job-1", customer = "cust-1", completedAt = null)
        val job = jobWithDropoffs("job-1", tasks = listOf(staleMirror))
        val retired = staleMirror.copy(completedAt = 3_000L)
        assertTrue(
            "completion truth comes from justRetired, not the stale mirror",
            isJobPhysicallyComplete(job, recentTasks = listOf(retired), justRetired = retired),
        )
    }

    @Test
    fun `predicate — an outstanding customer-TBD placeholder keeps the job incomplete`() {
        val delivered = dropoff("task-d1", "job-1", customer = "cust-1", completedAt = 3_000L)
        val tbd = dropoff("task-d2", "job-1", customer = null, completedAt = null)
        val job = jobWithDropoffs("job-1", tasks = listOf(delivered, tbd))
        assertFalse(isJobPhysicallyComplete(job, recentTasks = listOf(delivered), justRetired = null))
    }

    @Test
    fun `predicate — a zero-dropoff (pickup-only) job never qualifies`() {
        val pickup = Task("task-p", "job-1", TaskPhase.PICKUP, startedAt = 1_000L, completedAt = 2_000L)
        val job = jobWithDropoffs("job-1", tasks = listOf(pickup))
        assertFalse(isJobPhysicallyComplete(job, recentTasks = listOf(pickup), justRetired = null))
    }

    // ---- T1: retire-commit closes a complete job ----

    @Test
    fun `T1 — retiring the last dropoff closes the job (receipt-skip)`() {
        val drop = dropoff("task-d1", "job-1", customer = "cust-1")
        val job = jobWithDropoffs("job-1", tasks = listOf(drop))
        val region = onlineRegion(job).copy(
            activeTask = drop,
            recentTasks = emptyList(),
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE, since = 2_000L, deadline = 3_000L, armedFromFlow = Flow.Idle,
            ),
        )
        // GRACE_COMMIT past the deadline → lazy-expiry retire → T1 close.
        val r = stepper.step(region, FlowRegion(flow = Flow.Idle), FlowRegion(flow = Flow.Idle), graceTimeout(3_001L), policy)
        assertNull("a physically-complete job closes on the retire commit", r.activeJob)
        assertNull(r.activeTask)
        assertTrue("the drop completed into recentTasks", r.recentTasks.any { it.taskId == "task-d1" && it.completedAt != null })
    }

    @Test
    fun `T1 — retiring one drop while another placeholder is outstanding keeps the job open`() {
        val active = dropoff("task-d1", "job-1", customer = "cust-1")
        val outstanding = dropoff("task-d2", "job-1", customer = null, completedAt = null)
        val job = jobWithDropoffs("job-1", tasks = listOf(active, outstanding))
        val region = onlineRegion(job).copy(
            activeTask = active,
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE, since = 2_000L, deadline = 3_000L, armedFromFlow = Flow.Idle,
            ),
        )
        val r = stepper.step(region, FlowRegion(flow = Flow.Idle), FlowRegion(flow = Flow.Idle), graceTimeout(3_001L), policy)
        assertNotNull("an outstanding dropoff keeps the job open", r.activeJob)
        assertEquals("job-1", r.activeJob?.jobId)
    }

    @Test
    fun `T1 — an offer-armed retire does NOT close the still-undelivered final drop (amdt 4)`() {
        val drop = dropoff("task-d1", "job-1", customer = "cust-1")
        val job = jobWithDropoffs("job-1", tasks = listOf(drop))
        val region = onlineRegion(job).copy(
            activeTask = drop,
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE, since = 2_000L, deadline = 3_000L, armedFromFlow = Flow.OfferPresented,
            ),
        )
        val r = stepper.step(region, FlowRegion(flow = Flow.Idle), FlowRegion(flow = Flow.Idle), graceTimeout(3_001L), policy)
        assertNotNull("a retire armed by offer-deliberation must not close the job", r.activeJob)
        assertNull("the task still retires (it's the visual task closing), only the job survives", r.activeTask)
    }

    // ---- T2: accept never folds into a complete job ----

    @Test
    fun `T2 — accepting over a physically-complete job starts a NEW job`() {
        val delivered = dropoff("task-old", "job-old", customer = "cust-old", completedAt = 500L)
        val oldJob = jobWithDropoffs("job-old", tasks = listOf(delivered))
        val region = onlineRegion(oldJob).copy(activeTask = null, recentTasks = listOf(delivered))
        val r = stepAccept(region, acceptedOffer("offer-1"), acceptObs(2_000L, "Chipotle"))
        assertNotNull(r.activeJob)
        assertNotEquals("an accept over a complete job is an independent job, not an add-on", "job-old", r.activeJob?.jobId)
        assertEquals("the new job carries only the new offer", 1, r.activeJob?.acceptedOffers?.size)
    }

    @Test
    fun `T2 — accepting over an in-grace final drop closes it inline and starts a NEW job`() {
        // The final drop is still ACTIVE inside its TASK_RETIRE grace (idle-armed) at accept time.
        val drop = dropoff("task-old", "job-old", customer = "cust-old")
        val oldJob = jobWithDropoffs("job-old", tasks = listOf(drop))
        val region = onlineRegion(oldJob).copy(
            activeTask = drop,
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE, since = 1_500L, deadline = 11_500L, armedFromFlow = Flow.Idle,
            ),
        )
        val r = stepAccept(region, acceptedOffer("offer-1"), acceptObs(2_000L, "Chipotle"))
        assertNotEquals("the complete old job is closed; a fresh job is minted", "job-old", r.activeJob?.jobId)
        // Honest completion time = the grace-arm `since`, not obs.timestamp (retireActiveTask semantics).
        assertTrue(
            "the old drop was committed inline at pend.since",
            r.recentTasks.any { it.taskId == "task-old" && it.completedAt == 1_500L },
        )
    }

    @Test
    fun `T2 — accepting into an incomplete job still folds in as an add-on (add-on regression guard)`() {
        val active = dropoff("task-active", "job-old", customer = "cust-1", completedAt = null)
        val oldJob = jobWithDropoffs("job-old", tasks = listOf(active))
        val region = onlineRegion(oldJob).copy(activeTask = active)
        val r = stepAccept(region, acceptedOffer("offer-2"), acceptObs(2_000L, "Chipotle"))
        assertEquals("an add-on into an incomplete job stays the same job (#499/#503)", "job-old", r.activeJob?.jobId)
        assertEquals("the add-on offer accumulates onto the job", 2, r.activeJob?.acceptedOffers?.size)
    }

    @Test
    fun `T2 — accepting while the final drop has an OFFER-armed retire folds in as an add-on (amdt 4)`() {
        // The dasher is deliberating on THIS add-on offer (>10s), which armed a retire on the
        // undelivered final drop. That accept is the add-on the interlude belongs to — it must fold
        // in, not start a new job or complete the drop.
        val finalDrop = dropoff("task-final", "job-old", customer = "cust-1")
        val oldJob = jobWithDropoffs("job-old", tasks = listOf(finalDrop))
        val region = onlineRegion(oldJob).copy(
            activeTask = finalDrop,
            pendingDestructive = PendingDestructive(
                kind = DestructiveKind.TASK_RETIRE, since = 1_500L, deadline = 11_500L, armedFromFlow = Flow.OfferPresented,
            ),
        )
        val r = stepAccept(region, acceptedOffer("offer-2"), acceptObs(2_000L, "Chipotle"))
        assertEquals("an offer-armed retire means this accept folds in — same job", "job-old", r.activeJob?.jobId)
        assertEquals(2, r.activeJob?.acceptedOffers?.size)
    }
}
