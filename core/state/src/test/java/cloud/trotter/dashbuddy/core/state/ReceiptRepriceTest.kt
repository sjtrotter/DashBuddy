package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
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
    // Review round 2 (#1033) — the three emission defects Astra found
    // =====================================================================================

    /**
     * R1 — cross-job OWNERSHIP. `lastAnnouncedPostTaskTaskId` falls back to
     * `recentTasks.lastOrNull()`, so if job B is accepted and every one of its task screens is
     * MISSED, B's receipt is still anchored on job A's last drop. Without a positive ownership rule
     * B's money would be appended as a re-price of A.
     */
    @Test
    fun `R1 — a NEW job's receipt is never appended as a re-price of the closed job`() {
        val jobB = Job("J2", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 20_000L)
        // Job A closed off an un-itemized receipt; job B is now live but produced no task frames at
        // all, so the announce anchor still names A's drop.
        val region = closedJobRegion().copy(activeJob = jobB)
        assertTrue(
            "B's expanded receipt must not re-price A's drop",
            repriceEvents(region).isEmpty(),
        )
    }

    @Test
    fun `R1 — minting a new job CLEARS the closed-job marker in the stepper`() {
        val jobB = Job("J2", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 20_000L)
        val region = closedJobRegion().copy(activeJob = jobB)
        val next = stepper.step(
            region,
            FlowRegion(flow = Flow.PostTask),
            FlowRegion(flow = Flow.PostTask),
            postTaskObs(expanded(), 21_000L),
            policy,
        )
        assertNull(
            "the re-price window is strictly between the close and the next job's mint",
            next.lastClosedJobReceipt,
        )
        // …and the ordinary happy path is untouched: no live job, the marker survives and fires.
        assertEquals(1, repriceEvents(closedJobRegion()).size)
    }

    /**
     * R2 — the PostTask-exit mint can complete a task that is STILL ACTIVE under its retire grace
     * (`completedAt` is stamped only when that grace commits), so a `recentTasks` + `completedAt`
     * scan finds an empty denominator for exactly the job whose receipt is on screen.
     */
    @Test
    fun `R2 — a completion minted from the still-ACTIVE task is still re-priced`() {
        // 10_000: the collapsed receipt arms the 8 s window.
        val afterReceipt = armGrace(collapsed(), at = 10_000L)
        assertEquals(18_000L, afterReceipt.pendingDestructive?.deadline)

        // 11_000: an Idle frame exits PostTask → the job closes while the task is STILL ACTIVE with
        // its retire grace pending, and the completion is minted off the collapsed receipt.
        val idleObs = Observation.Screen(
            timestamp = 11_000L, captureId = "cap-idle", ruleId = "doordash.screen.idle",
            metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
            parsed = ParsedFields.None,
        )
        val afterExit = stepper.step(
            afterReceipt, FlowRegion(flow = Flow.PostTask), FlowRegion(flow = Flow.Idle), idleObs, policy,
        )
        val completions = effectMap
            .diff(appState(afterReceipt, Flow.PostTask), appState(afterExit, Flow.Idle), idleObs)
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_COMPLETED }
        assertEquals("the exit minted the completion", 1, completions.size)
        assertNull("off the COLLAPSED receipt", (completions.single().event.payload as DeliveryPayload).parsedPay)
        assertNull("the job closed", afterExit.activeJob)
        assertEquals("but the task is still ACTIVE", "t1", afterExit.activeTask?.taskId)
        assertNull("with no completedAt yet", afterExit.activeTask?.completedAt)
        assertEquals(DestructiveKind.TASK_RETIRE, afterExit.pendingDestructive?.kind)

        // 14_000: the expansion lands. The denominator has to include the still-active task.
        val events = repriceEvents(afterExit, at = 14_000L)
        assertEquals(1, events.size)
        assertEquals("t1", events.single().taskId)
        assertEquals(16.70, events.single().dropRealizedPay, 0.0001)
    }

    /**
     * R3 — the expansion that itself trips the lazy expiry. The retire commits FIRST (closing the
     * job and stamping the marker off the COLLAPSED receipt), then this frame's expanded parse is
     * stored — so the marker exists only in the RESULTING region, and the completion this same step
     * mints carries no itemization. Identical later renders are FrameGate-suppressed, so if this
     * frame emits nothing the delivery is never corrected at all.
     */
    @Test
    fun `R3 — an expansion that trips the lazy expiry re-prices on the SAME frame, after the completion`() {
        val afterReceipt = armGrace(collapsed(), at = 10_000L)
        assertEquals(18_000L, afterReceipt.pendingDestructive?.deadline)

        val obs = postTaskObs(expanded(), 18_001L) // one ms past the deadline
        val next = stepper.step(
            afterReceipt, FlowRegion(flow = Flow.PostTask), FlowRegion(flow = Flow.PostTask), obs, policy,
        )
        assertNull("the expiry closed the job on this frame", next.activeJob)
        assertNotNull("…stamping the marker in the RESULTING region only", next.lastClosedJobReceipt)
        assertTrue("off the collapsed receipt", !next.lastClosedJobReceipt!!.itemized)
        assertNull("while `prev` still has none", afterReceipt.lastClosedJobReceipt)

        val logged = effectMap
            .diff(appState(afterReceipt, Flow.PostTask), appState(next, Flow.PostTask), obs)
            .filterIsInstance<AppEffect.LogEvent>()
            .map { it.event }
        val types = logged.map { it.type }
        assertTrue(
            "the completion is minted on this frame: $types",
            types.contains(AppEventType.DELIVERY_COMPLETED),
        )
        assertNull(
            "and it carries the COLLAPSED receipt",
            (logged.first { it.type == AppEventType.DELIVERY_COMPLETED }.payload as DeliveryPayload).parsedPay,
        )
        val repriced = logged.filter { it.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
        assertEquals("exactly one re-price on the same frame: $types", 1, repriced.size)
        assertTrue(
            "ORDERING is load-bearing — the re-price must land after the completion it corrects",
            types.indexOf(AppEventType.DELIVERY_RECEIPT_REPRICE) >
                types.indexOf(AppEventType.DELIVERY_COMPLETED),
        )
        val e = repriced.single().payload as DeliveryReceiptRepricePayload
        assertEquals("t1", e.taskId)
        assertEquals(16.70, e.dropRealizedPay, 0.0001)
    }

    // =====================================================================================
    // Review round 3 (#1033) — ownership across an UNMINTED accept
    // =====================================================================================

    /**
     * A fold of real observations through the real stepper + `EffectMap`, tracking R0 the way
     * `StateMachine` does (a flow-less observation — a click — leaves the flow where it was). Round 3
     * is specifically about state that only ARISES from transitions (an accepted-pending-consumption
     * survivor, minted by the offer lifecycle on the leave-presentation edge), so these tests may not
     * hand-build it.
     */
    private inner class Fold(start: PlatformRegion) {
        var region: PlatformRegion = start
        private var flow: Flow = start.lastActedFlow ?: Flow.Idle
        val events = mutableListOf<AppEvent>()

        fun step(obs: Observation): Fold {
            val prevRegion = region
            val prevFlow = flow
            val nextFlow = (obs as? Observation.FlowObservation)?.flow ?: prevFlow
            region = stepper.step(prevRegion, FlowRegion(flow = prevFlow), FlowRegion(flow = nextFlow), obs, policy)
            events += effectMap
                .diff(appState(prevRegion, prevFlow), appState(region, nextFlow), obs)
                .filterIsInstance<AppEffect.LogEvent>()
                .map { it.event }
            flow = nextFlow
            return this
        }

        fun reprices() = events.filter { it.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
    }

    private fun idleObs(at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.idle",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    private fun offerObs(at: Long, hash: String) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.offer_popup",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(
                offerHash = hash, payAmount = 12.0, distanceMiles = 5.0,
                orders = listOf(
                    ParsedOrder(
                        orderIndex = 0, orderType = OrderType.PICKUP, storeName = "Next Store",
                        itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
                    ),
                ),
            ),
        ),
    )

    private fun acceptClick(at: Long) = Observation.Click(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.click.accept_offer",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
    )

    /** Drives job A to its close off a COLLAPSED receipt: the marker is stamped by real transitions. */
    private fun foldWithClosedJobA(): Fold {
        val fold = Fold(liveDropoffRegion())
            .step(postTaskObs(collapsed(), 10_000L)) // the collapsed receipt arms the 8 s window
            .step(idleObs(18_001L)) // past the deadline → retire commits, job A closes, marker stamped
        assertNull("job A closed", fold.region.activeJob)
        assertNotNull("…and stamped its marker", fold.region.lastClosedJobReceipt)
        return fold
    }

    /** The offer→accept→leave-presentation edge that mints an accepted-pending-consumption survivor. */
    private fun Fold.acceptNextOffer(offerAt: Long, clickAt: Long, leaveAt: Long): Fold {
        val jobBefore = region.activeJob?.jobId
        step(offerObs(offerAt, "hash-B"))
        step(acceptClick(clickAt))
        step(idleObs(leaveAt))
        assertTrue(
            "the accept produced an accepted-pending-consumption survivor",
            region.pendingOffers.any { it.acceptedAt != null },
        )
        // THE point of round 3: accept and mint are separate transitions. The survivor exists, but no
        // new job does — the mint waits for a TASK-flow frame, which these sequences never send.
        assertEquals(
            "the accept minted no NEW job — that is what `activeJob`-based ownership misses",
            jobBefore,
            region.activeJob?.jobId,
        )
        return this
    }

    @Test
    fun `round 3 — a next job accepted but never minted cannot re-price the closed job (Astra's sequence)`() {
        val fold = foldWithClosedJobA()
            .acceptNextOffer(offerAt = 20_000L, clickAt = 21_000L, leaveAt = 22_000L)
        assertTrue(
            "the accept is recorded on the marker, stickily",
            fold.region.lastClosedJobReceipt!!.acceptedSince,
        )

        // Step 5 of the sequence: the survivor EXPIRES (acceptGraceMs) long before B's receipt shows.
        fold.step(idleObs(600_000L))
        assertTrue("the survivor expired", fold.region.pendingOffers.none { it.acceptedAt != null })
        assertTrue(
            "…but the marker's flag is STICKY — that expiry must not re-open the window",
            fold.region.lastClosedJobReceipt!!.acceptedSince,
        )

        // B's $40 receipt finally renders. Every one of B's task screens was missed, so the announce
        // anchor still names A's last drop.
        val bigReceipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 30.00)),
            customerTips = listOf(ParsedPayItem("Next Store", 10.00)),
        )
        val before = fold.events.size
        fold.step(postTaskObs(expanded(bigReceipt), 622_000L))
        assertEquals(
            "B's money must never be appended as a re-price of A",
            0,
            fold.events.drop(before).count { it.type == AppEventType.DELIVERY_RECEIPT_REPRICE },
        )
    }

    @Test
    fun `round 3 — the STACKED case still re-prices - accept B on A's receipt, then expand A (same total)`() {
        // A's collapsed receipt is up; the dasher accepts the next offer from it, then expands A.
        val fold = Fold(liveDropoffRegion())
            .step(postTaskObs(collapsed(), 10_000L))
            .acceptNextOffer(offerAt = 11_000L, clickAt = 12_000L, leaveAt = 13_000L)
            .step(idleObs(18_001L)) // A's retire lapses → A closes
        assertTrue("the accept flagged the marker", fold.region.lastClosedJobReceipt!!.acceptedSince)

        val before = fold.events.size
        fold.step(postTaskObs(expanded(), 19_000L)) // A's OWN receipt, same $16.70 total
        val repriced = fold.events.drop(before).filter { it.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
        assertEquals("the same total is positive ownership — A is still re-priced", 1, repriced.size)
        assertEquals("t1", (repriced.single().payload as DeliveryReceiptRepricePayload).taskId)
    }

    @Test
    fun `round 3 — after an accept, a DIFFERENT total is ambiguous and re-prices nothing`() {
        val fold = foldWithClosedJobA()
            .acceptNextOffer(offerAt = 20_000L, clickAt = 21_000L, leaveAt = 22_000L)
        val other = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 15.00)),
            customerTips = listOf(ParsedPayItem("Somewhere", 7.00)),
        )
        val before = fold.events.size
        fold.step(postTaskObs(expanded(other), 25_000L))
        assertEquals(0, fold.events.drop(before).count { it.type == AppEventType.DELIVERY_RECEIPT_REPRICE })
    }

    @Test
    fun `round 3 — a receipt-LESS close leaves no total to own by, so an accept refuses (fail-null)`() {
        // The #596 receipt-skip shape: A closes with no receipt at all → the marker has no totalPay.
        val fold = Fold(liveDropoffRegion())
            .step(idleObs(10_000L)) // leaving the task flow arms the 10 s idle retire grace
            .step(idleObs(20_001L)) // lapses → A retires and closes
        assertNull("job A closed", fold.region.activeJob)
        assertNull("with no receipt ever seen", fold.region.lastClosedJobReceipt!!.totalPay)

        fold.acceptNextOffer(offerAt = 21_000L, clickAt = 22_000L, leaveAt = 23_000L)
        val before = fold.events.size
        fold.step(postTaskObs(expanded(), 25_000L))
        assertEquals(
            "no total to check ownership against — fail-null",
            0,
            fold.events.drop(before).count { it.type == AppEventType.DELIVERY_RECEIPT_REPRICE },
        )
    }

    @Test
    fun `round 3 — a FOREIGN platform's receipt never re-prices this region's drops`() {
        val region = closedJobRegion()
        val uberReceipt = Observation.Screen(
            timestamp = 21_000L, captureId = "cap-uber", ruleId = "uber.screen.delivery_summary",
            metadata = ReplayMetadata.EMPTY, flow = Flow.PostTask, modeHint = Mode.Online,
            parsed = expanded(),
        )
        assertEquals("the fixture really is a foreign frame", Platform.Uber, uberReceipt.platform)
        // Step the DoorDash region with it anyway (simulating the leak the guard defends against).
        val next = stepper.step(
            region, FlowRegion(flow = Flow.PostTask), FlowRegion(flow = Flow.PostTask), uberReceipt, policy,
        )
        val events = effectMap
            .diff(appState(region, Flow.PostTask), appState(next, Flow.PostTask), uberReceipt)
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
        assertTrue(events.isEmpty())
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
