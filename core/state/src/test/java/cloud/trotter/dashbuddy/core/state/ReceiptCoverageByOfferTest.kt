package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.analytics.RecordFolds
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.SequencedAppEvent
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.ClosedJobReceipt
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1120: a "This offer" receipt cannot spend another offer's delivered drops. */
class ReceiptCoverageByOfferTest {
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

    private fun region(subject: Task = b, siblings: List<Task> = listOf(a)): PlatformRegion {
        val tasks = siblings + subject
        return PlatformRegion(
            platform = Platform.DoorDash,
            activeJob = Job(
                jobId = "job", offerStoreHint = emptyList(), parentOfferHash = "H1", startedAt = 100L,
                acceptedOffers = tasks.mapNotNull { it.mintedByOfferHash }.distinct().map {
                    AcceptedOfferEconomics(offerHash = it, acceptedAt = 100L)
                },
                tasks = tasks,
            ),
            recentTasks = siblings, activeTask = subject,
        )
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
    fun `one offer stack still covers and pools both delivered drops`() {
        val region = region(subject = b.copy(mintedByOfferHash = "H1"))
        assertEquals(setOf("A", "B"), receiptCoverageAt(region, "B").taskIds)
        assertEquals(mapOf("A" to 10.25, "B" to 10.25), reprices(region).associate { it.taskId to it.dropRealizedPay })
    }

    @Test
    fun `hash-less subject covers itself even with hash-less completed siblings`() {
        for (sibling in listOf(a, a.copy(mintedByOfferHash = null))) {
            val region = region(subject = b.copy(mintedByOfferHash = null), siblings = listOf(sibling))
            assertEquals(setOf("B"), receiptCoverageAt(region, "B").taskIds)
            assertEquals(mapOf("B" to 20.50), reprices(region).associate { it.taskId to it.dropRealizedPay })
        }
    }

    @Test
    fun `unassigned sibling never joins even with completion and matching offer`() {
        val region = region(subject = b.copy(mintedByOfferHash = "H1"), siblings = listOf(a.copy(unassignedAt = 3_100L)))
        assertEquals(setOf("B"), receiptCoverageAt(region, "B").taskIds)
    }

    @Test
    fun `same offer sibling still needs completion evidence and accountable identity`() {
        for (sibling in listOf(a.copy(completedAt = null), a.copy(customerNameHash = null), a.copy(phase = TaskPhase.PICKUP))) {
            assertEquals(setOf("B"), receiptCoverageAt(region(b.copy(mintedByOfferHash = "H1"), listOf(sibling)), "B").taskIds)
        }
    }

    @Test
    fun `same hash cannot cover a different job and missing subject covers nothing`() {
        val region = region(b.copy(mintedByOfferHash = "H1"), listOf(a.copy(jobId = "earlier-job")))
        assertEquals(setOf("B"), receiptCoverageAt(region, "B").taskIds)
        assertTrue(receiptCoverageAt(region, null).taskIds.isEmpty())
        assertTrue(receiptCoverageAt(region, "missing").taskIds.isEmpty())
    }

    private fun reprices(live: PlatformRegion): List<DeliveryReceiptRepricePayload> {
        val subject = live.activeTask!!.copy(completedAt = 4_000L)
        val closed = live.copy(
            activeJob = null, activeTask = null, recentTasks = live.recentTasks + subject,
            lastClosedJobReceipt = ClosedJobReceipt(jobId = "job", receiptSeenAt = 3_500L),
        )
        // Post-close coverage must resolve the subject's hash/job from recentTasks too.
        val coverage = receiptCoverageAt(closed, subject.taskId)
        assertEquals(receiptCoverageAt(live, subject.taskId), coverage)
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
