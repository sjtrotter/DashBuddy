package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.analytics.RecordFolds
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.ClosedJobReceipt
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.JobReceiptAnchors
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.ReceiptCoverage
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1120: a "This offer" receipt cannot spend another offer's delivered drops. */
class ReceiptCoverageByJobTest {
    private val receipt = ParsedPay(
        appPayComponents = listOf(ParsedPayItem("Base Pay", 20.50)), customerTips = emptyList(),
    )
    private val a = drop("A", "H1", 3_000L)
    private val b = drop("B", "H2", null)

    private fun drop(id: String, hash: String?, completedAt: Long?) = Task(
        taskId = id, jobId = "job", phase = TaskPhase.DROPOFF,
        customerNameHash = "customer-$id", startedAt = 2_000L,
        completedAt = completedAt, mintedByOfferHash = hash,
    )

    private fun region(subject: Task = b, siblings: List<Task> = listOf(a), offers: List<String> = listOf("H1", "H2")): PlatformRegion {
        val tasks = siblings + subject
        return PlatformRegion(
            platform = Platform.DoorDash,
            activeJob = Job(
                jobId = "job", offerStoreHint = emptyList(), parentOfferHash = "H1", startedAt = 100L,
                acceptedOffers = offers.map {
                    AcceptedOfferEconomics(offerHash = it, acceptedAt = 100L)
                },
                tasks = tasks,
            ),
            recentTasks = siblings, activeTask = subject,
        )
    }

    @Test
    fun `other offer activates first open placeholder but receipts remain subject-only`() {
        val placeholderA = a.copy(customerNameHash = null, completedAt = null)
        val placeholderB = b.copy(customerNameHash = null)
        val initial = region(subject = placeholderB, siblings = listOf(placeholderA)).copy(
            activeTask = null, recentTasks = emptyList(),
        )
        val obs = Observation.Screen(
            timestamp = 2_500L, captureId = "blind-bind", ruleId = "dropoff",
            metadata = ReplayMetadata.EMPTY,
            flow = Flow.TaskDropoffArrived, modeHint = null,
            parsed = ParsedFields.TaskFields(
                phase = TaskPhase.DROPOFF,
                subFlow = TaskSubFlow.ARRIVED,
                storeName = "Offer B store", customerNameHash = "customer-from-B",
            ),
        )
        val bound = PlatformRegionStepper().updateTaskLifecycle(
            initial, Flow.Idle,
            Flow.TaskDropoffArrived, obs, TransitionPolicy(),
        )
        val otherOffer = bound.activeTask!!
        assertEquals("A", otherOffer.taskId)
        assertEquals("H1", otherOffer.mintedByOfferHash)
        assertEquals("customer-from-B", otherOffer.customerNameHash)
        assertEquals(setOf("A"), receiptCoverageAt(bound, "A").taskIds)
        val firstShares = reprices(bound)
        val later = region(subject = b, siblings = listOf(otherOffer.copy(completedAt = 3_000L)))
        assertEquals(setOf("B"), receiptCoverageAt(later, "B").taskIds)
        val laterShares = reprices(later)
        assertEquals(setOf("A"), firstShares.map { it.taskId }.toSet())
        assertEquals(setOf("B"), laterShares.map { it.taskId }.toSet())
        assertEquals(41.0, (firstShares + laterShares).sumOf { it.dropRealizedPay }, 0.0)
    }

    @Test
    fun `two offers cover only the subject even after the earlier receipt folded`() {
        // The first offer's $28.51 is already real money; coverage must not select its row again.
        val folded = RecordFolds.foldEvent(
            SequencedAppEvent(sequenceId = 1L, event = AppEvent(
                type = AppEventType.DELIVERY_COMPLETED, occurredAt = 3_000L, sessionId = null,
                payload = DeliveryPayload(
                    jobId = "job", taskId = "A", phaseStartedAt = 2_000L,
                    completedAt = 3_000L, totalPay = 28.51, dropRealizedPay = 28.51,
                ),
            )), context = null, currentCostPerMile = null,
        ).delivery!!
        assertEquals(28.51, folded.realizedPay!!, 0.0)
        assertEquals(setOf("B"), receiptCoverageAt(region(), "B").taskIds)
        val events = reprices(region())
        assertEquals(mapOf("B" to 20.50), events.associate { it.taskId to it.dropRealizedPay })
        assertTrue("no DELIVERY_RECEIPT_REPRICE targets A's folded receipt", events.none { it.taskId == folded.taskId })
    }

    @Test
    fun `single offer pools both drops regardless of mismatched or missing hints`() {
        for (hint in listOf("H1", "H2", null)) {
            val region = region(subject = b.copy(mintedByOfferHash = hint), offers = listOf("H1"))
            assertEquals(setOf("A", "B"), receiptCoverageAt(region, "B").taskIds)
            assertEquals(mapOf("A" to 10.25, "B" to 10.25), reprices(region).associate { it.taskId to it.dropRealizedPay })
        }
    }

    @Test
    fun `two offers cover only subject even when all hints are missing`() {
        for (sibling in listOf(a, a.copy(mintedByOfferHash = null))) {
            val region = region(subject = b.copy(mintedByOfferHash = null), siblings = listOf(sibling))
            assertEquals(setOf("B"), receiptCoverageAt(region, "B").taskIds)
            assertEquals(mapOf("B" to 20.50), reprices(region).associate { it.taskId to it.dropRealizedPay })
        }
    }

    @Test
    fun `unassigned sibling never joins even with completion and matching offer`() {
        val region = region(subject = b.copy(mintedByOfferHash = "H1"), siblings = listOf(a.copy(unassignedAt = 3_100L)), offers = listOf("H1"))
        assertEquals(setOf("B"), receiptCoverageAt(region, "B").taskIds)
    }

    @Test
    fun `same offer sibling still needs completion evidence and accountable identity`() {
        for (sibling in listOf(a.copy(completedAt = null), a.copy(customerNameHash = null), a.copy(phase = TaskPhase.PICKUP))) {
            assertEquals(setOf("B"), receiptCoverageAt(region(b.copy(mintedByOfferHash = "H1"), listOf(sibling), offers = listOf("H1")), "B").taskIds)
        }
    }

    @Test
    fun `same hash cannot cover a different job and missing subject covers nothing`() {
        val region = region(b.copy(mintedByOfferHash = "H1"), listOf(a.copy(jobId = "earlier-job")))
        assertEquals(setOf("B"), receiptCoverageAt(region, "B").taskIds)
        assertTrue(receiptCoverageAt(region, null).taskIds.isEmpty())
        assertTrue(receiptCoverageAt(region, "missing").taskIds.isEmpty())
    }

    @Test
    fun `two offers sharing a wrong hint still cover subject only`() {
        val region = region(subject = b.copy(mintedByOfferHash = "H1"))
        assertEquals(setOf("B"), receiptCoverageAt(region, "B").taskIds)
        assertEquals(mapOf("B" to 20.50), reprices(region).associate { it.taskId to it.dropRealizedPay })
    }

    @Test
    fun `post-close or mismatched job covers subject only`() {
        val live = region(offers = listOf("H1"))
        val closed = live.copy(activeJob = null, activeTask = null, recentTasks = listOf(a, b.copy(completedAt = 4_000L)))
        assertEquals(setOf("B"), receiptCoverageAt(closed, "B").taskIds)
        assertEquals(setOf("B"), receiptCoverageAt(live.copy(activeJob = live.activeJob!!.copy(jobId = "other")), "B").taskIds)
    }

    @Test
    fun `unassigned subject never enters coverage`() {
        val region = region(subject = b.copy(unassignedAt = 4_000L))
        assertTrue(receiptCoverageAt(region, "B").taskIds.isEmpty())
    }

    @Test
    fun `restored wide coverage is intersected before cached reprice`() {
        val source = region(subject = b.copy(completedAt = 4_000L, mintedByOfferHash = "H1")).copy(
            lastPostTaskFields = ParsedFields.PostTaskFields(totalPay = receipt.total, parsedPay = receipt),
            lastPostTaskCoverage = ReceiptCoverage(setOf("A", "B")),
            lastAnnouncedPostTaskTaskId = "B",
        )
        val closed = source.copy(
            activeJob = null, activeTask = null, recentTasks = source.recentTasks + source.activeTask!!,
            lastClosedJobReceipt = ClosedJobReceipt(jobId = "job", receiptSeenAt = 3_500L),
        )
        val next = PlatformRegionStepper().decideFromCachedReceipt(source, closed, decidedAt = 5_000L)
        assertEquals(mapOf("B" to 20.50), next.pendingReceiptReprice!!.shares)
        val payloads = EffectMap().diffReceiptReprice(closed, next).filterIsInstance<AppEffect.LogEvent>()
            .map { it.event.payload as DeliveryReceiptRepricePayload }
        assertEquals(mapOf("B" to 20.50), payloads.associate { it.taskId to it.dropRealizedPay })
    }

    @Test
    fun `cached single offer coverage still pools both drops at actual job close`() {
        val source = cacheReceipt(
            region(subject = b.copy(completedAt = 4_000L), offers = listOf("H1")).copy(
                activeTask = null, recentTasks = listOf(a, b.copy(completedAt = 4_000L)),
                jobReceiptAnchors = JobReceiptAnchors(jobId = "job", firstEnteredAt = 3_500L),
            ),
            ParsedFields.PostTaskFields(totalPay = receipt.total, parsedPay = receipt), "B",
        )
        val next = PlatformRegionStepper().completeActiveJob(source, at = 5_000L)
        assertEquals(mapOf("A" to 10.25, "B" to 10.25), next.pendingReceiptReprice!!.shares)
    }

    private fun reprices(live: PlatformRegion): List<DeliveryReceiptRepricePayload> {
        val subject = live.activeTask!!.copy(completedAt = 4_000L)
        val closed = live.copy(
            activeJob = null, activeTask = null, recentTasks = live.recentTasks + subject,
            lastClosedJobReceipt = ClosedJobReceipt(jobId = "job", receiptSeenAt = 3_500L),
        )
        // Coverage captured while the job was live is passed to the decision at close.
        val coverage = receiptCoverageAt(live, subject.taskId)
        val next = PlatformRegionStepper().decideReceiptReprice(
            region = closed,
            parsed = ParsedFields.PostTaskFields(totalPay = receipt.total, parsedPay = receipt),
            postTaskTaskId = subject.taskId, coverage = coverage, decidedAt = 5_000L, captureId = null,
        )
        val events = EffectMap().diffReceiptReprice(closed, next).filterIsInstance<AppEffect.LogEvent>()
        assertTrue(events.all { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE })
        val payloads = events.map { it.event.payload as DeliveryReceiptRepricePayload }
        assertEquals(next.pendingReceiptReprice!!.shares, payloads.associate { it.taskId to it.dropRealizedPay })
        return payloads
    }
}
