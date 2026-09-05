package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.settings.GraceConfig
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.ClosedJobReceipt
import cloud.trotter.dashbuddy.domain.state.CrossPlatformRegion
import cloud.trotter.dashbuddy.domain.state.DestructiveKind
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1033 — a collapsed post-delivery receipt gets time to be expanded (layer 1), and an expansion
 * that still lands after the completion re-prices the drop from the receipt (layer 2).
 *
 * The field shape (2026-08-23): the receipt rendered COLLAPSED, the authoritative 2.5 s retire grace
 * committed the completion off it (so the drop was priced by the #691 `OFFER_PAY` estimate), and the
 * expansion — carrying the real itemization — landed 3.9 s after the collapsed frame, 1.3 s late.
 */
class ReceiptRepriceTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()
    private val effectMap = EffectMap()

    private val receipt = ParsedPay(
        appPayComponents = listOf(ParsedPayItem("Base Pay", 9.70)),
        customerTips = listOf(ParsedPayItem("Bill Millers", 7.00)),
    )

    private fun collapsed(total: Double = 16.70) =
        ParsedFields.PostTaskFields(totalPay = total, parsedPay = null, isExpanded = false)

    private fun expanded(pay: ParsedPay = receipt) =
        ParsedFields.PostTaskFields(totalPay = pay.total, parsedPay = pay, isExpanded = true)

    private fun postTaskObs(parsed: ParsedFields, timestamp: Long) = Observation.Screen(
        timestamp = timestamp,
        captureId = "cap-$timestamp",
        ruleId = "doordash.screen.delivery_summary",
        metadata = ReplayMetadata.EMPTY,
        flow = Flow.PostTask,
        modeHint = Mode.Online,
        parsed = parsed,
    )

    private fun dropoff(id: String, store: String, completedAt: Long? = 4_000L) = Task(
        taskId = id,
        jobId = "J1",
        phase = TaskPhase.DROPOFF,
        storeName = store,
        customerNameHash = "cust-$id",
        startedAt = 3_000L,
        completedAt = completedAt,
    )

    private fun appState(region: PlatformRegion, flow: Flow) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow),
            platforms = mapOf(Platform.DoorDash to region),
            crossPlatform = CrossPlatformRegion(),
        ),
    )

    // =====================================================================================
    // Layer 1 — the collapsed receipt's retire grace
    // =====================================================================================

    /** A region sitting on a live dropoff, about to see the receipt. */
    private fun liveDropoffRegion() = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("S1", startedAt = 100L),
        // The job owns its one dropoff placeholder + the drop ARRIVED — what the #596/#615 strict
        // completeness arm needs before a retire may close the job.
        activeJob = Job(
            "J1",
            offerStoreHint = emptyList(),
            parentOfferHash = null,
            startedAt = 200L,
            tasks = listOf(dropoff("t1", "Bill Millers", completedAt = null)),
        ),
        activeTask = dropoff("t1", "Bill Millers", completedAt = null).copy(arrivedAt = 3_500L),
        lastActedFlow = Flow.TaskDropoffArrived,
    )

    private fun armGrace(parsed: ParsedFields, at: Long): PlatformRegion = stepper.step(
        liveDropoffRegion(),
        FlowRegion(flow = Flow.TaskDropoffArrived),
        FlowRegion(flow = Flow.PostTask),
        postTaskObs(parsed, at),
        policy,
    )

    @Test
    fun `a COLLAPSED receipt arms the longer receipt-expand grace`() {
        val next = armGrace(collapsed(), at = 10_000L)
        assertEquals(DestructiveKind.TASK_RETIRE, next.pendingDestructive?.kind)
        assertEquals(
            10_000L + GraceConfig.RECEIPT_EXPAND_GRACE_MS,
            next.pendingDestructive?.deadline,
        )
    }

    @Test
    fun `an EXPANDED receipt keeps the short authoritative grace`() {
        val next = armGrace(expanded(), at = 10_000L)
        assertEquals(
            10_000L + GraceConfig.AUTHORITATIVE_GRACE_MS,
            next.pendingDestructive?.deadline,
        )
    }

    @Test
    fun `a PostTask frame that parses no receipt keeps the pre-1033 authoritative grace`() {
        val next = armGrace(ParsedFields.None, at = 10_000L)
        assertEquals(
            10_000L + GraceConfig.AUTHORITATIVE_GRACE_MS,
            next.pendingDestructive?.deadline,
        )
    }

    @Test
    fun `a same-task expansion inside the window lands its itemization in the commit and tightens the deadline`() {
        // 10_000: collapsed receipt → deadline 18_000 (was 12_500 pre-#1033).
        val afterCollapsed = armGrace(collapsed(), at = 10_000L)
        assertEquals(18_000L, afterCollapsed.pendingDestructive?.deadline)
        assertNull("the collapsed receipt carries no itemization", afterCollapsed.lastPostTaskFields?.parsedPay)

        // 14_000 (+4 s, past the OLD 12_500 deadline): the expansion lands.
        val afterExpanded = stepper.step(
            afterCollapsed,
            FlowRegion(flow = Flow.PostTask),
            FlowRegion(flow = Flow.PostTask),
            postTaskObs(expanded(), 14_000L),
            policy,
        )
        assertNotNull(
            "the expansion landed in lastPostTaskFields — the commit will read it",
            afterExpanded.lastPostTaskFields?.parsedPay,
        )
        assertEquals(
            "an expanded frame tightens the widened deadline back to the authoritative window",
            14_000L + GraceConfig.AUTHORITATIVE_GRACE_MS,
            afterExpanded.pendingDestructive?.deadline,
        )
        assertEquals(
            "the task is still live — the commit has not happened yet",
            "t1",
            afterExpanded.activeTask?.taskId,
        )
    }

    // =====================================================================================
    // Layer 2 — the post-commit expansion
    // =====================================================================================

    /** The region as it stands AFTER the collapsed receipt's grace committed and closed the job. */
    private fun closedJobRegion(
        drops: List<Task> = listOf(dropoff("t1", "Bill Millers")),
        mark: ClosedJobReceipt? = ClosedJobReceipt(jobId = "J1", totalPay = 16.70, itemized = false),
        announceId: String? = "t1",
    ) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("S1", startedAt = 100L),
        activeJob = null,
        activeTask = null,
        recentTasks = drops,
        lastActedFlow = Flow.PostTask,
        lastAnnouncedPostTaskTaskId = announceId,
        lastClosedJobReceipt = mark,
    )

    private fun repriceEvents(
        prevRegion: PlatformRegion,
        parsed: ParsedFields = expanded(),
        at: Long = 21_000L,
    ): List<DeliveryReceiptRepricePayload> {
        val obs = postTaskObs(parsed, at)
        val next = stepper.step(
            prevRegion,
            FlowRegion(flow = Flow.PostTask),
            FlowRegion(flow = Flow.PostTask),
            obs,
            policy,
        )
        return effectMap.diff(appState(prevRegion, Flow.PostTask), appState(next, Flow.PostTask), obs)
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
            .map { it.event.payload as DeliveryReceiptRepricePayload }
    }

    @Test
    fun `an expansion after the commit emits exactly one re-price carrying the drop's share`() {
        val events = repriceEvents(closedJobRegion())
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals("J1", e.jobId)
        assertEquals("t1", e.taskId)
        assertEquals(16.70, e.totalPay, 0.0001)
        assertEquals("a sole drop takes the whole receipt", 16.70, e.dropRealizedPay, 0.0001)
        assertEquals(receipt, e.parsedPay)
        assertEquals("cap-21000", e.sourceCaptureId)
    }

    @Test
    fun `a stacked receipt re-prices every delivered sibling and the shares sum to the total`() {
        val stackedReceipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 8.00)),
            customerTips = listOf(
                ParsedPayItem("Bill Millers", 6.00),
                ParsedPayItem("Maple Street", 2.01),
            ),
        )
        val region = closedJobRegion(
            drops = listOf(dropoff("t1", "Bill Millers"), dropoff("t2", "Maple Street")),
            mark = ClosedJobReceipt(jobId = "J1", totalPay = stackedReceipt.total, itemized = false),
        )
        val events = repriceEvents(region, parsed = expanded(stackedReceipt))
        assertEquals("one event per delivered drop", 2, events.size)
        assertEquals(setOf("t1", "t2"), events.map { it.taskId }.toSet())
        assertEquals(
            "Σ shares == the receipt total, to the cent",
            Math.round(stackedReceipt.total * 100.0),
            events.sumOf { Math.round(it.dropRealizedPay * 100.0) },
        )
    }

    @Test
    fun `no re-price when the completion already carried this itemization`() {
        val region = closedJobRegion(
            mark = ClosedJobReceipt(jobId = "J1", totalPay = receipt.total, itemized = true),
        )
        assertTrue(repriceEvents(region).isEmpty())
    }

    @Test
    fun `an itemized completion at a DIFFERENT total still re-prices`() {
        val region = closedJobRegion(
            mark = ClosedJobReceipt(jobId = "J1", totalPay = 12.00, itemized = true),
        )
        assertEquals(1, repriceEvents(region).size)
    }

    @Test
    fun `a COLLAPSED re-render after the commit emits nothing (no new evidence)`() {
        assertTrue(repriceEvents(closedJobRegion(), parsed = collapsed()).isEmpty())
    }

    @Test
    fun `no re-price while the job is still OPEN — that is layer 1's path`() {
        val open = closedJobRegion().copy(
            activeJob = Job("J1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L),
        )
        assertTrue(
            "the pending mint will carry the itemization itself",
            repriceEvents(open).isEmpty(),
        )
    }

    @Test
    fun `no re-price without the closed-job marker (fail-null)`() {
        assertTrue(repriceEvents(closedJobRegion(mark = null)).isEmpty())
    }

    @Test
    fun `no re-price for a drop that was never completed`() {
        val region = closedJobRegion(drops = listOf(dropoff("t1", "Bill Millers", completedAt = null)))
        assertTrue(repriceEvents(region).isEmpty())
    }

    @Test
    fun `an UNASSIGNED drop is never re-priced (the 736 firewall)`() {
        val region = closedJobRegion(
            drops = listOf(dropoff("t1", "Bill Millers").copy(unassignedAt = 4_500L)),
        )
        assertTrue(repriceEvents(region).isEmpty())
    }

    @Test
    fun `a receipt anchored on another job's drop is not evidence about these rows`() {
        // The stepper re-stamps the announce anchor from the LAST retired task, so the way this
        // guard is actually reached in the field is a foreign task sitting last in `recentTasks`.
        val foreign = dropoff("tX", "Somewhere Else").copy(jobId = "J9")
        val region = closedJobRegion(drops = listOf(dropoff("t1", "Bill Millers"), foreign))
        assertTrue(repriceEvents(region).isEmpty())
    }

    @Test
    fun `the effect key is per (taskId, itemization) so a repeat frame dedups`() {
        val region = closedJobRegion()
        val obs = postTaskObs(expanded(), 21_000L)
        val next = stepper.step(region, FlowRegion(flow = Flow.PostTask), FlowRegion(flow = Flow.PostTask), obs, policy)
        val keys = effectMap.diff(appState(region, Flow.PostTask), appState(next, Flow.PostTask), obs)
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
            .map { it.effectKeyOverride }
        assertEquals(
            listOf("log:${AppEventType.DELIVERY_RECEIPT_REPRICE}:t1:${receipt.hashCode()}"),
            keys,
        )
    }

    // =====================================================================================
    // The marker itself
    // =====================================================================================

    @Test
    fun `closing a job stamps what its completions' receipt looked like`() {
        // A collapsed receipt is on file; the retire grace lapses and closes the physically-complete job.
        val armed = liveDropoffRegion().copy(
            lastPostTaskFields = collapsed(),
            lastAnnouncedPostTaskTaskId = "t1",
        )
        val afterReceipt = stepper.step(
            armed,
            FlowRegion(flow = Flow.TaskDropoffArrived),
            FlowRegion(flow = Flow.PostTask),
            postTaskObs(collapsed(), 10_000L),
            policy,
        )
        // The GRACE_COMMIT timer lands past the widened deadline → retire + T1 job close.
        val afterCommit = stepper.step(
            afterReceipt,
            FlowRegion(flow = Flow.PostTask),
            FlowRegion(flow = Flow.PostTask),
            Observation.Timeout(
                timestamp = 18_001L,
                type = cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.GRACE_COMMIT,
                targetPlatform = Platform.DoorDash,
            ),
            policy,
        )
        assertNull("the job closed", afterCommit.activeJob)
        assertNull("the close clears the receipt — which is why the marker exists", afterCommit.lastPostTaskFields)
        val mark = afterCommit.lastClosedJobReceipt
        assertNotNull(mark)
        assertEquals("J1", mark!!.jobId)
        assertEquals(16.70, mark.totalPay!!, 0.0001)
        assertTrue("the completions were priced off an UN-itemized receipt", !mark.itemized)
    }
}
