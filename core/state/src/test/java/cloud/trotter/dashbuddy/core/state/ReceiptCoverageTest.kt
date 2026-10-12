package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.AcceptedOfferEconomics
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.JobReceiptAnchors
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.ReceiptCoverage
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1120: completed siblings pool until priced by their own receipt, including after job close. */
class ReceiptCoverageTest {
    private val stepper = PlatformRegionStepper()
    private val a = drop("A", 3_000L)
    private val b = drop("B", 4_000L)

    private fun drop(id: String, completedAt: Long?) = Task(
        taskId = id, jobId = "job", phase = TaskPhase.DROPOFF,
        customerNameHash = "customer-$id", startedAt = 2_000L,
        completedAt = completedAt,
    )

    private fun fields(amount: Double) = ParsedFields.PostTaskFields(
        totalPay = amount,
        parsedPay = ParsedPay(listOf(ParsedPayItem("Base Pay", amount)), emptyList()),
    )

    private fun region(subject: Task = b, siblings: List<Task> = listOf(a), offers: Int = 2): PlatformRegion {
        val tasks = siblings + subject
        return PlatformRegion(
            platform = Platform.DoorDash, mode = Mode.Online, session = Session("session", 100L),
            activeJob = Job(
                jobId = "job", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 100L,
                acceptedOffers = (1..offers).map {
                    AcceptedOfferEconomics(offerHash = "offer-$it", acceptedAt = 100L)
                }, tasks = tasks,
            ),
            recentTasks = siblings, activeTask = subject,
            jobReceiptAnchors = JobReceiptAnchors(jobId = "job", firstEnteredAt = 3_500L),
        )
    }

    private fun read(region: PlatformRegion, subject: String, amount: Double) =
        cacheReceipt(region, fields(amount), subject)

    private fun exit(source: PlatformRegion, at: Long = 4_600L): PlatformRegion = stepper.step(
        source.copy(lastActedFlow = Flow.PostTask), FlowRegion(flow = Flow.PostTask),
        FlowRegion(flow = Flow.TaskDropoffNavigation), Observation.Screen(
            timestamp = at, captureId = "exit", ruleId = "idle", metadata = ReplayMetadata.EMPTY,
            flow = Flow.TaskDropoffNavigation, modeHint = null, parsed = ParsedFields.None,
        ), TransitionPolicy(),
    )

    private fun retired(source: PlatformRegion) =
        source.copy(activeTask = null, recentTasks = source.recentTasks + listOfNotNull(source.activeTask))

    private fun close(source: PlatformRegion) = stepper.completeActiveJob(
        retired(source),
        at = 5_000L,
    )

    private fun state(region: PlatformRegion, flow: Flow = Flow.Idle) = AppState(
        regions = Regions(flow = FlowRegion(flow = flow, activePlatform = Platform.DoorDash),
            platforms = mapOf(Platform.DoorDash to region)),
    )

    private fun events(before: PlatformRegion, after: PlatformRegion, from: Flow = Flow.Idle) =
        EffectMap().diff(state(before.copy(lastActedFlow = from), from),
            state(after.copy(lastActedFlow = Flow.Idle)), Observation.Screen(
            timestamp = 5_000L, captureId = "close", ruleId = "idle", metadata = ReplayMetadata.EMPTY,
            flow = Flow.Idle, modeHint = null, parsed = ParsedFields.None,
        )).filterIsInstance<AppEffect.LogEvent>().map { it.event }

    private fun minted(before: PlatformRegion, after: PlatformRegion, from: Flow = Flow.Idle) =
        events(before, after, from).filter { it.type == AppEventType.DELIVERY_COMPLETED }
            .map { it.payload as DeliveryPayload }

    @Test
    fun `09-20 first drop own receipt is final when second receipt arrives`() {
        val first = read(region(a, emptyList()), "A", 28.51)
        val pricedA = exit(first).activeTask!!
        val later = read(region(b, listOf(pricedA)), "B", 20.50)
        assertEquals(setOf("B"), later.lastPostTaskCoverage!!.taskIds)
        assertEquals(mapOf("B" to 20.50), close(later).pendingReceiptReprice!!.shares)
        assertEquals(4_600L, later.recentTasks.single().receiptPricedAt)
    }

    @Test
    fun `single offer two drop receipt pools both regardless of hints or observed offer count`() {
        for (offers in listOf(0, 1, 2)) for (hint in listOf(null, "wrong-offer")) {
            val source = read(region(b.copy(mintedByOfferHash = hint), offers = offers), "B", 20.0)
            assertEquals(setOf("A", "B"), source.lastPostTaskCoverage!!.taskIds)
            assertEquals(mapOf("A" to 10.0, "B" to 10.0), close(source).pendingReceiptReprice!!.shares)
        }
    }

    @Test
    fun `Astra a X keeps own receipt while Y1 and Y2 share twenty dollars - total thirty`() {
        val xRead = read(region(drop("X", 3_000L), emptyList()), "X", 10.0)
        val xRow = minted(xRead, xRead, Flow.PostTask).single()
        assertEquals(10.0, xRow.dropRealizedPay!!, 0.0)
        val x = exit(xRead).activeTask!!
        val source = read(region(drop("Y2", 4_000L), listOf(x, drop("Y1", 3_500L))), "Y2", 20.0)
        assertEquals(setOf("Y1", "Y2"), source.lastPostTaskCoverage!!.taskIds)
        val closed = close(source)
        assertEquals(mapOf("Y1" to 10.0, "Y2" to 10.0), closed.pendingReceiptReprice!!.shares)
        val yRows = minted(retired(source), closed).filter { it.taskId != "X" }
        assertEquals(setOf("Y1", "Y2"), yRows.map { it.taskId }.toSet())
        assertTrue(yRows.all { it.offerPayShare == null })
        assertEquals(30.0, xRow.dropRealizedPay!! + yRows.sumOf { it.dropRealizedPay!! }, 0.0)
        assertEquals(x, closed.recentTasks.first { it.taskId == "X" })
    }

    @Test
    fun `Astra b post-close combined update reprices both pooled drops - no thirty two dollars`() {
        val source = read(region(offers = 2), "B", 20.0)
        val closed = close(source)
        val original = closed.pendingReceiptReprice!!.shares
        assertEquals(mapOf("A" to 10.0, "B" to 10.0), original)
        val updated = stepper.step(
            closed.copy(pendingReceiptReprice = null), FlowRegion(flow = Flow.Idle),
            FlowRegion(flow = Flow.PostTask), Observation.Screen(
                timestamp = 6_000L, captureId = "update", ruleId = "receipt", metadata = ReplayMetadata.EMPTY,
                flow = Flow.PostTask, modeHint = null, parsed = fields(22.0),
            ), TransitionPolicy(),
        )
        assertEquals(source.lastPostTaskCoverage!!.taskIds, updated.lastPostTaskCoverage!!.taskIds)
        assertEquals(mapOf("A" to 11.0, "B" to 11.0), updated.pendingReceiptReprice!!.shares)
        val corrections = EffectMap().diffReceiptReprice(closed, updated).filterIsInstance<AppEffect.LogEvent>()
            .map { it.event.payload as DeliveryReceiptRepricePayload }
        val totals = original.toMutableMap()
        corrections.forEach { totals[it.taskId] = it.dropRealizedPay }
        assertEquals(22.0, totals.values.sum(), 0.0)
    }

    @Test
    fun `Astra c restored wide coverage excludes B already priced - mint and reprice agree`() {
        val bRead = exit(read(region(b, emptyList()), "B", 10.0))
        val source = region(a, listOf(bRead.activeTask!!)).copy(
            lastPostTaskFields = fields(20.0), lastPostTaskCoverage = ReceiptCoverage(setOf("A", "B")),
            lastAnnouncedPostTaskTaskId = "A",
        )
        assertEquals(setOf("A"), source.cachedReceiptCoverage()!!.taskIds)
        val inline = minted(source, source, Flow.PostTask).single()
        assertEquals(20.0, inline.dropRealizedPay!!, 0.0)
        val closed = close(source)
        val rows = minted(retired(source), closed)
        assertNull(rows.single { it.taskId == "B" }.totalPay)
        val shares = rows.filter { it.dropRealizedPay != null }.associate { it.taskId to it.dropRealizedPay }
        assertEquals(mapOf("A" to 20.0), shares)
        assertEquals(shares, closed.pendingReceiptReprice!!.shares)
    }

    @Test
    fun `unassigned drops never covered as subject or sibling`() {
        val abandoned = a.copy(unassignedAt = 3_100L)
        assertEquals(setOf("B"), receiptCoverageAt(region(b, listOf(abandoned)), "B").taskIds)
        assertTrue(receiptCoverageAt(region(abandoned, emptyList()), "A").taskIds.isEmpty())
    }

    @Test
    fun `siblings need completion accountability and same job - subject needs no completion`() {
        for (sibling in listOf(a.copy(completedAt = null), a.copy(customerNameHash = null),
            a.copy(phase = TaskPhase.PICKUP), a.copy(jobId = "other"))) {
            assertEquals(setOf("B"), receiptCoverageAt(region(b.copy(completedAt = null), listOf(sibling)), "B").taskIds)
        }
        assertTrue(receiptCoverageAt(region(), null).taskIds.isEmpty())
        assertTrue(receiptCoverageAt(region(), "missing").taskIds.isEmpty())
    }


    @Test
    fun `a read alone never stamps active or recent drops including a singleton`() {
        for (subject in listOf("A", "B")) {
            val source = region(b.copy(completedAt = null))
            val cached = read(source, subject, 20.0)
            assertNull(cached.activeTask!!.receiptPricedAt)
            assertTrue(cached.recentTasks.all { it.receiptPricedAt == null })
            assertTrue(stepper.reconcileJobTasks(cached).activeJob!!.tasks.all { it.receiptPricedAt == null })
        }
        val singleton = read(region(b, emptyList()), "B", 20.0)
        assertNull(singleton.activeTask!!.receiptPricedAt)
        val taken = exit(singleton)
        assertEquals(4_600L, taken.activeTask!!.receiptPricedAt)
        assertEquals(4_600L, taken.activeJob!!.tasks.single().receiptPricedAt)
        val collapsed = cacheReceipt(region(b, emptyList()), ParsedFields.PostTaskFields(totalPay = 20.0), "B")
        assertNull(exit(collapsed).activeTask!!.receiptPricedAt)
    }

    @Test
    fun `A single or unobserved offer repeats unsplit twenty without doubling - whole job final shape`() {
        for (offers in listOf(0, 1)) {
            val openB = b.copy(completedAt = null)
            val first = read(region(a, emptyList(), offers).let {
                it.copy(activeJob = it.activeJob!!.copy(tasks = listOf(a, openB)))
            }, "A", 20.0)
            assertEquals(setOf("A"), first.lastPostTaskCoverage!!.taskIds)
            val firstRow = minted(first, first, Flow.PostTask).single()
            assertNull(firstRow.dropRealizedPay)
            assertNull(firstRow.totalPay)
            val afterExit = exit(first)
            assertNull(afterExit.activeTask!!.receiptPricedAt)
            val final = read(afterExit.copy(activeTask = b, recentTasks = listOf(a),
                activeJob = afterExit.activeJob!!.copy(tasks = listOf(a, b))), "B", 20.0)
            assertEquals(setOf("A", "B"), final.lastPostTaskCoverage!!.taskIds)
            val closed = close(final)
            assertEquals(mapOf("A" to 10.0, "B" to 10.0), closed.pendingReceiptReprice!!.shares)
            assertEquals(20.0, closed.pendingReceiptReprice!!.shares.values.sum(), 0.0)
            assertTrue(closed.recentTasks.all { it.receiptPricedAt == 5_000L })
            // A never consults stamps, even if restored from an earlier receipt decision.
            assertEquals(setOf("A", "B"), receiptCoverageAt(closed, "B").taskIds)
        }
    }

    @Test
    fun `C multi-offer X ten then Y unsplit twenty re-shown after each drop totals thirty`() {
        val xRead = read(region(drop("X", 3_000L), emptyList()), "X", 10.0)
        val x = exit(xRead).activeTask!!
        val y1 = drop("Y1", 3_500L)
        val y2 = drop("Y2", 4_000L)
        val first = read(region(y1, listOf(x)).let {
            it.copy(activeJob = it.activeJob!!.copy(tasks = listOf(x, y1, y2.copy(completedAt = null))))
        }, "Y1", 20.0)
        assertEquals(setOf("Y1"), first.lastPostTaskCoverage!!.taskIds)
        assertEquals(20.0, minted(first, first, Flow.PostTask).single().dropRealizedPay!!, 0.0)
        val taken = exit(first)
        val pricedY1 = taken.activeTask!!
        val final = read(taken.copy(activeTask = y2, recentTasks = listOf(x, pricedY1),
            activeJob = taken.activeJob!!.copy(tasks = listOf(x, pricedY1, y2))), "Y2", 20.0)
        assertEquals(setOf("Y1", "Y2"), final.cachedReceiptCoverage()!!.taskIds)
        assertEquals(10.0, minted(final, final, Flow.PostTask).single().dropRealizedPay!!, 0.0)
        val closed = close(final)
        val shares = closed.pendingReceiptReprice!!.shares
        assertEquals(mapOf("Y1" to 10.0, "Y2" to 10.0), shares)
        assertEquals(30.0, 10.0 + shares.values.sum(), 0.0)
        assertEquals(x, closed.recentTasks.first { it.taskId == "X" })
        assertEquals(4_600L, closed.recentTasks.first { it.taskId == "Y1" }.receiptPricedAt)
        assertEquals(5_000L, closed.recentTasks.first { it.taskId == "Y2" }.receiptPricedAt)
    }

    @Test
    fun `C equality compares the itemization not just the total`() {
        val first = exit(read(region(a, emptyList()), "A", 10.0))
        val pricedA = first.activeTask!!
        val source = first.copy(activeTask = b, recentTasks = listOf(pricedA),
            activeJob = first.activeJob!!.copy(tasks = listOf(pricedA, b)))
        val changed = ParsedFields.PostTaskFields(totalPay = 10.0,
            parsedPay = ParsedPay(listOf(ParsedPayItem("Base Pay", 4.0)),
                listOf(ParsedPayItem("Tip", 6.0))))
        assertEquals(setOf("B"), cacheReceipt(source, changed, "B").lastPostTaskCoverage!!.taskIds)
    }

    @Test
    fun `C repeats extend the prior pool by the subject without adding unrelated unpriced drops`() {
        val first = exit(read(region(a, emptyList()), "A", 10.0))
        val pricedA = first.activeTask!!
        val skipped = drop("skipped", 3_500L)
        val source = first.copy(activeTask = b, recentTasks = listOf(pricedA, skipped),
            activeJob = first.activeJob!!.copy(tasks = listOf(pricedA, skipped, b)))
        val repeated = read(source, "B", 10.0)
        assertEquals(setOf("A", "B"), repeated.lastPostTaskCoverage!!.taskIds)
        assertEquals(mapOf("A" to 5.0, "B" to 5.0), close(repeated).pendingReceiptReprice!!.shares)
    }

    @Test
    fun `C different total starts new receipt with subject and unpriced siblings`() {
        val first = exit(read(region(a, emptyList()), "A", 10.0))
        val unpriced = drop("skipped", 3_500L)
        val source = first.copy(activeTask = b, recentTasks = listOf(first.activeTask!!, unpriced),
            activeJob = first.activeJob!!.copy(tasks = listOf(first.activeTask!!, unpriced, b)))
        val next = read(source, "B", 20.0)
        assertEquals(setOf("skipped", "B"), next.lastPostTaskCoverage!!.taskIds)
        assertEquals(mapOf("B" to 10.0, "skipped" to 10.0), close(next).pendingReceiptReprice!!.shares)
    }
}
