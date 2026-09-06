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
import cloud.trotter.dashbuddy.domain.state.JobReceiptAnchors
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PendingReceiptReprice
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Regions
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        mark: ClosedJobReceipt? = ClosedJobReceipt(jobId = "J1", receiptSeenAt = 9_000L),
        announceId: String? = "t1",
        acceptResolvedAt: Long? = null,
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
        jobReceiptAnchors = mark?.receiptSeenAt?.let {
            JobReceiptAnchors(jobId = mark.jobId, firstEnteredAt = it)
        },
        lastAcceptResolvedAt = acceptResolvedAt,
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
            mark = ClosedJobReceipt(jobId = "J1", receiptSeenAt = 9_000L),
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
    fun `a receipt whose itemization the MINT already carried still emits — the projector no-ops it`() {
        // Round 8: the stepper stopped modelling what the rows hold (two rounds of fail-null). A
        // redundant "the receipt says X" event is honest and cheap; `applyReceiptReprice` compares the
        // row and skips without rewriting it.
        assertEquals(1, repriceEvents(closedJobRegion()).size)
    }

    @Test
    fun `the stepper suppresses only its OWN repeated decision`() {
        val region = closedJobRegion()
        val obs = postTaskObs(expanded(), 21_000L)
        val after = stepper.step(region, FlowRegion(flow = Flow.PostTask), FlowRegion(flow = Flow.PostTask), obs, policy)
        assertEquals(receipt, after.lastClosedJobReceipt!!.lastDecidedPay)
        assertEquals(1, after.lastClosedJobReceipt!!.repriceRevision)
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
    fun `the effect key is per (taskId, jobId, decision revision)`() {
        val region = closedJobRegion()
        val obs = postTaskObs(expanded(), 21_000L)
        val next = stepper.step(region, FlowRegion(flow = Flow.PostTask), FlowRegion(flow = Flow.PostTask), obs, policy)
        val keys = effectMap.diff(appState(region, Flow.PostTask), appState(next, Flow.PostTask), obs)
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
            .map { it.effectKeyOverride }
        assertEquals(
            listOf("log:${AppEventType.DELIVERY_RECEIPT_REPRICE}:t1:J1:r1"),
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
        assertNull("while `prev` still has none", afterReceipt.lastClosedJobReceipt)
        // ORDERING inside the step is what makes this work (round 4): `stepCore`'s lazy expiry
        // closes the job — stamping the marker off the COLLAPSED receipt — strictly before
        // `updateSessionFields` stores this frame's expanded parse and decides the re-price. The
        // marker therefore ends the step already updated to the expanded figures.
        assertEquals("the same frame re-priced it", 1, next.lastClosedJobReceipt!!.repriceRevision)
        assertEquals(receipt, next.lastClosedJobReceipt!!.lastDecidedPay)

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
    // Ownership (#1033 review rounds 3–6) — "has any accept resolved since this receipt appeared?"
    //
    // A receipt carries no job identity. Rounds 2–5 each tried a heuristic — the announce anchor, an
    // accepted-since flag, a matching total — and each leaked in turn. Round 6 narrowed the rule to
    // the one temporal question that is answerable, and every scenario those rounds found lives on
    // here as a case of it.
    // =====================================================================================

    /**
     * A fold of real observations through the real stepper + `EffectMap`, tracking R0 the way
     * `StateMachine` does (a flow-less observation — a click — leaves the flow where it was). These
     * scenarios turn on state that only ARISES from transitions (an accepted-pending-consumption
     * survivor, minted by the offer lifecycle on the leave-presentation edge), so they may not
     * hand-build it.
     */
    private inner class Fold(start: PlatformRegion) {
        var region: PlatformRegion = start
        private var flow: Flow = start.lastActedFlow ?: Flow.Idle
        val events = mutableListOf<AppEvent>()
        /** Re-price effect keys as the engine's `effects_fired` table would see them. */
        val effectKeys = mutableListOf<String?>()
        /** EVERY log effect's (type, durable key) — the fake `effects_fired` these tests dedup against. */
        val allKeys = mutableListOf<Pair<AppEventType, String?>>()

        fun step(obs: Observation): Fold {
            val prevRegion = region
            val prevFlow = flow
            val nextFlow = (obs as? Observation.FlowObservation)?.flow ?: prevFlow
            region = stepper.step(prevRegion, FlowRegion(flow = prevFlow), FlowRegion(flow = nextFlow), obs, policy)
            val logged = effectMap
                .diff(appState(prevRegion, prevFlow), appState(region, nextFlow), obs)
                .filterIsInstance<AppEffect.LogEvent>()
            events += logged.map { it.event }
            effectKeys += logged
                .filter { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
                .map { it.effectKeyOverride }
            allKeys += logged.map { it.event.type to it.effectKeyOverride }
            flow = nextFlow
            return this
        }

        fun reprices() = events.filter { it.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
    }

    /**
     * The whole machine — the round-5 findings turn on the ORDER `step` runs its stages in
     * (`stepOffers` before `stepCore`, the post-step hook after both), which only a real transition
     * exercises.
     */
    private inner class MachineFold(start: PlatformRegion, startFlow: Flow) {
        private val machine = StateMachine(
            flowStepper = FlowRegionStepper(),
            platformStepper = stepper,
            crossPlatformStepper = CrossPlatformRegionStepper(),
            transitionPolicy = policy,
            effectMap = effectMap,
        )
        private var state: AppState = appState(start, startFlow)
        val events = mutableListOf<AppEvent>()

        fun step(obs: Observation): MachineFold {
            val t = machine.step(state, obs)
            state = t.newState
            events += t.effects.filterIsInstance<AppEffect.LogEvent>().map { it.event }
            return this
        }

        val region: PlatformRegion get() = state.regions.platforms.getValue(Platform.DoorDash)
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

    /** Job A driven to its close off a COLLAPSED receipt, with NO acceptance anywhere. */
    private fun machineWithClosedJobA() =
        MachineFold(liveDropoffRegion(), Flow.TaskDropoffArrived)
            .step(postTaskObs(collapsed(), 10_000L)) // the receipt appears → receiptSeenAt
            .step(idleObs(18_001L)) // past the 8 s window → A retires and closes
            .also {
                assertNull("A closed", it.region.activeJob)
                assertEquals(
                    "the marker anchors on when the receipt appeared",
                    10_000L,
                    it.region.lastClosedJobReceipt!!.receiptSeenAt,
                )
            }

    // ---- the path that still works ------------------------------------------------------

    @Test
    fun `a late expansion with NO accept anywhere re-prices normally`() {
        val fold = machineWithClosedJobA().step(postTaskObs(expanded(), 19_000L))
        assertEquals(1, fold.reprices().size)
        assertEquals(
            "t1",
            (fold.reprices().single().payload as DeliveryReceiptRepricePayload).taskId,
        )
        assertNull("no acceptance ever resolved", fold.region.lastAcceptResolvedAt)
    }

    // ---- everything an acceptance closes off ---------------------------------------------

    @Test
    fun `round 4 P1 — an accept resolved BEFORE the close still closes the window`() {
        // The dasher takes the next offer straight off the receipt that is still up; the job closes
        // afterwards. The accept resolves at 13,000 >= the receipt's 10,000, so the window is shut —
        // no timestamp-vs-close comparison, and no marker needed to exist yet.
        val fold = MachineFold(liveDropoffRegion(), Flow.TaskDropoffArrived)
            .step(postTaskObs(collapsed(), 10_000L))
            .step(offerObs(11_000L, "hash-B"))
            .step(acceptClick(12_000L))
            .step(postTaskObs(collapsed(), 13_000L)) // back to A's receipt → the accept RESOLVES here
            .step(idleObs(18_001L)) // A retires and closes
        assertEquals(13_000L, fold.region.lastAcceptResolvedAt)
        // The receipt was left for the offer and came BACK, but the anchor FREEZES on the job's FIRST
        // appearance (round 7). Re-stamping would move the cutoff forward to 13,000 — and a later
        // cutoff admits MORE receipts, since fewer accepts then satisfy `resolvedAt >= receiptSeenAt`.
        assertEquals(10_000L, fold.region.lastClosedJobReceipt!!.receiptSeenAt)

        fold.step(postTaskObs(expanded(), 19_000L)) // A's OWN receipt, same $16.70 total
        assertEquals(
            "STACKED late expansion is FAIL-NULL: layer 1's 8 s window is the path that lands it",
            0,
            fold.reprices().size,
        )
    }

    @Test
    fun `round 6 — an accept whose survivor EXPIRED before the close still closes the window`() {
        // The marker did not exist when the accept resolved, and the survivor is long gone by the
        // time the receipt expands — neither of which the machine is allowed to forget.
        val fold = MachineFold(liveDropoffRegion(), Flow.TaskDropoffArrived)
            .step(postTaskObs(collapsed(), 10_000L))
            .step(offerObs(11_000L, "hash-B"))
            .step(acceptClick(12_000L))
            .step(postTaskObs(collapsed(), 13_000L)) // accept resolves; A still ACTIVE, no marker yet
            .step(idleObs(200_000L)) // A's retire lapses and closes; the survivor has expired
        assertTrue("the survivor expired", fold.region.pendingOffers.none { it.acceptedAt != null })
        assertEquals("…but the acceptance is remembered", 13_000L, fold.region.lastAcceptResolvedAt)

        fold.step(postTaskObs(expanded(), 210_000L))
        assertEquals(0, fold.reprices().size)
    }

    @Test
    fun `round 6 — a foreign receipt with a MATCHING total cannot redistribute the closed job's drops`() {
        // The same-total exception was withdrawn: a total is not identity. B's $16.70 receipt would
        // otherwise install B's tip/base split — a real $10/$10 pair re-cut as $5/$15.
        val fold = machineWithClosedJobA()
            .step(offerObs(20_000L, "hash-B"))
            .step(acceptClick(21_000L))
            .step(idleObs(22_000L))
        assertEquals(22_000L, fold.region.lastAcceptResolvedAt)

        fold.step(postTaskObs(expanded(), 25_000L)) // B's receipt, coincidentally $16.70
        assertEquals(0, fold.reprices().size)
    }

    @Test
    fun `round 5 — an accept resolving ON the next job's receipt frame closes the window too`() {
        // `step` resolves offers BEFORE `stepCore`, so this ONE frame both resolves B's accept latch
        // and parses B's receipt.
        val fold = machineWithClosedJobA()
            .step(offerObs(20_000L, "hash-B"))
            .step(acceptClick(21_000L))
        assertNull("nothing has resolved yet", fold.region.lastAcceptResolvedAt)

        val bigReceipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 30.00)),
            customerTips = listOf(ParsedPayItem("Next Store", 10.00)),
        )
        fold.step(postTaskObs(expanded(bigReceipt), 622_000L))
        assertEquals("B's money is never appended as a re-price of A", 0, fold.reprices().size)
        assertEquals(622_000L, fold.region.lastAcceptResolvedAt)
    }

    @Test
    fun `round 5 — the same-frame accept cannot reduce an ALREADY re-priced job either`() {
        val fold = machineWithClosedJobA()
            .step(postTaskObs(expanded(), 19_000L)) // re-price #1 → $16.70
        assertEquals(1, fold.reprices().size)

        val updated = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 9.70)),
            customerTips = listOf(ParsedPayItem("Bill Millers", 10.30)),
        )
        fold.step(postTaskObs(expanded(updated), 20_000L)) // re-price #2 → $20.00
        assertEquals(2, fold.reprices().size)
        assertEquals(2, fold.region.lastClosedJobReceipt!!.repriceRevision)

        fold.step(offerObs(21_000L, "hash-B"))
            .step(acceptClick(22_000L))
            .step(postTaskObs(expanded(), 23_000L)) // B's receipt totals the ORIGINAL $16.70
        assertEquals("no third re-price", 2, fold.reprices().size)
        assertEquals(
            "A's last decision stands — nothing re-priced it back down",
            2,
            fold.region.lastClosedJobReceipt!!.repriceRevision,
        )
    }

    @Test
    fun `a receipt-LESS close has no anchor to own by, so it re-prices nothing (fail-null)`() {
        val fold = Fold(liveDropoffRegion())
            .step(idleObs(10_000L)) // leaving the task flow arms the 10 s idle retire grace
            .step(idleObs(20_001L)) // lapses → A retires and closes, with no receipt ever seen
        assertNull("job A closed", fold.region.activeJob)
        assertNull("no PostTask was ever entered", fold.region.lastClosedJobReceipt!!.receiptSeenAt)

        fold.step(postTaskObs(expanded(), 25_000L))
        assertEquals(0, fold.reprices().size)
    }

    // ---- round 7: both anchors freeze on the job's FIRST occurrence ----------------------

    @Test
    fun `round 7 — an offer overlay between two receipt frames cannot move the ownership cutoff`() {
        // The accept resolves DURING the overlay, then the receipt returns. If the anchor re-stamped
        // on that re-entry the cutoff would jump past the accept and admit the next job's receipt —
        // a later cutoff admits MORE, because fewer accepts satisfy `resolvedAt >= receiptSeenAt`.
        val fold = MachineFold(liveDropoffRegion(), Flow.TaskDropoffArrived)
            .step(postTaskObs(collapsed(), 10_000L)) // A's receipt FIRST appears
            .step(offerObs(11_000L, "hash-B")) // an offer overlays it
            .step(acceptClick(12_000L))
            .step(idleObs(12_500L)) // the accept RESOLVES here
            .step(postTaskObs(collapsed(), 13_000L)) // A's receipt RETURNS — a re-entry, not a new one
            .step(idleObs(18_001L)) // A retires and closes
        assertEquals(12_500L, fold.region.lastAcceptResolvedAt)
        assertEquals(
            "the anchor froze on the FIRST appearance",
            10_000L,
            fold.region.lastClosedJobReceipt!!.receiptSeenAt,
        )

        val bigReceipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 30.00)),
            customerTips = listOf(ParsedPayItem("Next Store", 10.00)),
        )
        fold.step(postTaskObs(expanded(bigReceipt), 622_000L))
        assertEquals("12,500 >= 10,000 — the window is shut", 0, fold.reprices().size)
    }

    @Test
    fun `round 9 — a job that closes with an ITEMIZED receipt on file decides at the close itself`() {
        // Astra's sequence, ENDING at the close — no further receipt frame ever renders. The first
        // completion was minted UN-itemized on the overlay's PostTask exit, the close's re-emission is
        // dropped by the per-taskId durable key, and `completeActiveJob` is about to clear
        // `lastPostTaskFields`. If the close does not decide, the row keeps its estimate forever.
        val updated = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 9.70)),
            customerTips = listOf(ParsedPayItem("Bill Millers", 10.30)),
        )
        val fold = Fold(liveDropoffRegion())
            .step(postTaskObs(collapsed(), 10_000L)) // A's collapsed receipt
            .step(offerObs(11_000L, "hash-B")) // an UNACCEPTED offer overlays it → the exit mints
            .step(postTaskObs(expanded(updated), 13_000L)) // the receipt returns EXPANDED ($20)

        val beforeClose = fold.reprices().size
        assertEquals("the job is still open, so no decision yet", 0, beforeClose)

        fold.step(idleObs(14_000L)) // the close — and the LAST step of the sequence

        val repriced = fold.reprices()
        assertEquals("the close decided from the receipt it was about to drop", 1, repriced.size)
        val payload = repriced.single().payload as DeliveryReceiptRepricePayload
        assertEquals("t1", payload.taskId)
        assertEquals(20.00, payload.totalPay, 0.0001)
        assertEquals("the row the projector will write", 20.00, payload.dropRealizedPay, 0.0001)

        // Ordering on that same step: the completion first, then the correction to it.
        val types = fold.events.map { it.type }
        assertTrue(
            "the re-price must land after the completion: $types",
            types.lastIndexOf(AppEventType.DELIVERY_RECEIPT_REPRICE) >
                types.lastIndexOf(AppEventType.DELIVERY_COMPLETED),
        )
        // Both completions shared one durable key, so only the UN-itemized first one was ever written
        // — which is exactly why the close-time decision is what carries the itemization.
        val completionKeys = fold.allKeys.filter { it.first == AppEventType.DELIVERY_COMPLETED }.map { it.second }
        assertEquals("both completions share ONE durable key: $completionKeys", 1, completionKeys.toSet().size)
        assertNull(
            "…and the first carried no itemization",
            (fold.events.first { it.type == AppEventType.DELIVERY_COMPLETED }.payload as DeliveryPayload).parsedPay,
        )
    }

    @Test
    fun `round 9 — the close-time decision is suppressed once it has already been decided`() {
        // The frame at 13,000 decides nothing (job open); the close decides. A LATER identical frame
        // must not decide again — `lastDecidedPay` is the stepper's own-decision suppression.
        val fold = Fold(liveDropoffRegion())
            .step(postTaskObs(collapsed(), 10_000L))
            .step(idleObs(18_001L)) // A retires and closes; the receipt on file is COLLAPSED → no decision
        assertEquals(0, fold.reprices().size)
        assertNull(fold.region.lastClosedJobReceipt!!.lastDecidedPay)

        fold.step(postTaskObs(expanded(), 19_000L))
        assertEquals(1, fold.reprices().size)
        fold.step(postTaskObs(expanded(), 20_000L))
        assertEquals("an unchanged re-render decides nothing", 1, fold.reprices().size)
        assertEquals(1, fold.region.lastClosedJobReceipt!!.repriceRevision)
    }

    @Test
    fun `round 8 — a two-drop job's late receipt apportions across BOTH rows`() {
        // One task's completion cannot describe a multi-drop job — which is why the marker stopped
        // trying. The whole-job receipt re-prices every delivered drop, $10/$10, under distinct
        // durable keys.
        val stacked = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 4.00)),
            customerTips = listOf(
                ParsedPayItem("Bill Millers", 8.00),
                ParsedPayItem("Maple Street", 8.00),
            ),
        )
        val region = closedJobRegion(
            drops = listOf(dropoff("t1", "Bill Millers"), dropoff("t2", "Maple Street")),
        )
        val fold = Fold(region).step(postTaskObs(expanded(stacked), 21_000L))

        val payloads = fold.reprices().map { it.payload as DeliveryReceiptRepricePayload }
        assertEquals("one event per delivered drop", 2, payloads.size)
        assertEquals(setOf("t1", "t2"), payloads.map { it.taskId }.toSet())
        payloads.forEach { assertEquals(10.00, it.dropRealizedPay, 0.0001) }
        assertEquals(
            "Σ shares == the receipt total",
            Math.round(stacked.total * 100.0),
            payloads.sumOf { Math.round(it.dropRealizedPay * 100.0) },
        )
        // The fake durable table: one key per drop, so both rows are written.
        val fired = mutableSetOf<String?>()
        assertEquals(2, fold.effectKeys.count { fired.add(it) })
    }

    // ---- round 10: the terminal session end, and structural decision equality --------------

    private fun sessionEndedObs(at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.dash_summary",
        metadata = ReplayMetadata.EMPTY, flow = Flow.SessionEnded, modeHint = Mode.Offline,
        parsed = ParsedFields.SessionEndedFields(totalEarnings = 40.0),
    )

    private fun graceCommit(at: Long) = Observation.Timeout(
        timestamp = at,
        type = cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.GRACE_COMMIT,
        targetPlatform = Platform.DoorDash,
    )

    @Test
    fun `round 10 — a SESSION END decides from its cached receipt before tearing the dash down`() {
        // `endSession` bypasses `completeActiveJob` entirely, so round 9's close-time decision never
        // ran on this path — and then the teardown cleared the cached receipt. The drop's completion
        // was minted at the estimate on the overlay's PostTask exit, so its row is the one that needs
        // the correction.
        val fold = Fold(liveDropoffRegion())
            .step(postTaskObs(collapsed(), 10_000L)) // A's collapsed receipt
            .step(offerObs(11_000L, "hash-B")) // an UNACCEPTED offer overlays → the exit mints
            .step(postTaskObs(expanded(), 13_000L)) // the receipt returns EXPANDED
            .step(sessionEndedObs(14_000L)) // the summary arms the SESSION_END grace
        assertEquals(DestructiveKind.SESSION_END, fold.region.pendingDestructive?.kind)
        assertEquals("nothing decided while the dash is still open", 0, fold.reprices().size)

        fold.step(graceCommit(16_501L)) // the grace commits → endSession

        assertNull("the dash is over", fold.region.session)
        val repriced = fold.reprices()
        assertEquals("the teardown decided from the receipt it dropped", 1, repriced.size)
        val payload = repriced.single().payload as DeliveryReceiptRepricePayload
        assertEquals("t1", payload.taskId)
        assertEquals(16.70, payload.totalPay, 0.0001)
        assertEquals(16.70, payload.dropRealizedPay, 0.0001)
    }

    @Test
    fun `round 10 — the OFFLINE-timeout variant of the teardown decides too`() {
        // Same shape, reached through the offline grace rather than the summary screen.
        val fold = Fold(liveDropoffRegion())
            .step(postTaskObs(collapsed(), 10_000L))
            .step(offerObs(11_000L, "hash-B"))
            .step(postTaskObs(expanded(), 13_000L))
            .step(
                Observation.Screen(
                    timestamp = 14_000L, captureId = "cap-off", ruleId = "doordash.screen.idle",
                    metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Offline,
                    parsed = ParsedFields.None,
                ),
            )
        assertEquals(DestructiveKind.SESSION_END, fold.region.pendingDestructive?.kind)

        fold.step(graceCommit(fold.region.pendingDestructive!!.deadline + 1))
        assertNull(fold.region.session)
        assertEquals("the offline teardown decided too", 1, fold.reprices().size)
    }

    @Test
    fun `round 11 — a MID-STACK teardown re-prices only the receipt's own anchor drop`() {
        // Drop 1 is delivered and anchors the cached receipt; drop 2 is ACTIVE and undelivered (the
        // bail is about to force-complete it). Widening the denominator to "the active task" put drop
        // 2 in it, and `apportion` split drop 1's $20 receipt $10/$10 across both — money moved.
        val bigReceipt = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 10.00)),
            customerTips = listOf(ParsedPayItem("Bill Millers", 10.00)),
        )
        val drop1 = dropoff("t1", "Bill Millers", completedAt = 11_000L)
        val drop2 = dropoff("t2", "Maple Street", completedAt = null).copy(arrivedAt = null)
        val region = PlatformRegion(
            platform = Platform.DoorDash,
            mode = Mode.Online,
            session = Session("S1", startedAt = 100L),
            activeJob = Job(
                "J1", offerStoreHint = emptyList(), parentOfferHash = null, startedAt = 200L,
                tasks = listOf(drop1, drop2),
            ),
            activeTask = drop2, // undelivered, about to be force-completed
            recentTasks = listOf(drop1), // delivered, its completion already minted
            lastActedFlow = Flow.PostTask,
            lastAnnouncedPostTaskTaskId = "t1", // the receipt on file is DROP 1's
            lastPostTaskFields = expanded(bigReceipt),
            // The job already left PostTask once, so the teardown widening is armed.
            jobReceiptAnchors = JobReceiptAnchors(jobId = "J1", firstEnteredAt = 10_000L, exitedPostTask = true),
        )

        val fold = Fold(region)
            .step(sessionEndedObs(14_000L))
            .step(graceCommit(16_501L))

        val repriced = fold.reprices()
        assertEquals("only the anchor drop is re-priced", 1, repriced.size)
        val payload = repriced.single().payload as DeliveryReceiptRepricePayload
        assertEquals("t1", payload.taskId)
        assertEquals(
            "…at the FULL receipt total — drop 2 never entered the denominator",
            20.00,
            payload.dropRealizedPay,
            0.0001,
        )
        assertTrue("drop 2 is untouched", repriced.none { (it.payload as DeliveryReceiptRepricePayload).taskId == "t2" })
    }

    @Test
    fun `round 10 — a task the BAIL force-completed is never re-priced`() {
        // The drop never reached a PostTask exit, so the mint never ran for it and there is no row to
        // correct. `endSession` force-stamps its `completedAt` as pure teardown bookkeeping (the
        // amdt-#5 T3 guard keeps the close-out sweep off it too) — the re-price must agree.
        val fold = Fold(liveDropoffRegion())
            .step(postTaskObs(expanded(), 10_000L)) // an itemized receipt IS on file…
            .step(sessionEndedObs(11_000L)) // …but the flow never LEFT PostTask
        val beforeTeardown = fold.reprices().size
        fold.step(graceCommit(fold.region.pendingDestructive!!.deadline + 1))

        assertNull(fold.region.session)
        assertEquals(
            "the bail's own force-completion is not a delivery",
            beforeTeardown,
            fold.reprices().size,
        )
    }

    @Test
    fun `round 10 — two receipts whose hashes COLLIDE are two distinct decisions`() {
        // base $2.05 + tip $5.50 and base $2.00 + tip $5.60 share Kotlin's -1866903064. A hash
        // comparison suppressed the second and froze the row at $7.55.
        val x = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 2.05)),
            customerTips = listOf(ParsedPayItem("Bill Millers", 5.50)),
        )
        val y = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 2.00)),
            customerTips = listOf(ParsedPayItem("Bill Millers", 5.60)),
        )
        assertEquals("the fixture really is a collision", x.hashCode(), y.hashCode())
        assertTrue("…of two DIFFERENT receipts", x != y)

        val fold = Fold(closedJobRegion())
            .step(postTaskObs(expanded(x), 21_000L))
            .step(postTaskObs(expanded(y), 22_000L))

        assertEquals("both decided", 2, fold.reprices().size)
        assertEquals(
            listOf(
                "log:DELIVERY_RECEIPT_REPRICE:t1:J1:r1",
                "log:DELIVERY_RECEIPT_REPRICE:t1:J1:r2",
            ),
            fold.effectKeys,
        )
        assertEquals(
            "the row ends at Y",
            7.60,
            (fold.reprices().last().payload as DeliveryReceiptRepricePayload).totalPay,
            0.0001,
        )
        assertEquals(y, fold.region.lastClosedJobReceipt!!.lastDecidedPay)
    }

    // ---- revision keying + the handoff ---------------------------------------------------

    @Test
    fun `an X to Y to X itemization emits THREE distinct effect keys and the row follows the marker`() {
        // The key used to fold in the receipt's hash, so the third emission collided with the first
        // and the durable `effects_fired` idempotency DROPPED it — the row stuck at Y while the
        // marker said X. Asserted against distinct keys (what that table sees), not the event count.
        val y = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 9.70)),
            customerTips = listOf(ParsedPayItem("Bill Millers", 10.30)),
        )
        val fold = Fold(closedJobRegion())
            .step(postTaskObs(expanded(), 21_000L)) // X = $16.70
            .step(postTaskObs(expanded(y), 22_000L)) // Y = $20.00
            .step(postTaskObs(expanded(), 23_000L)) // X again

        assertEquals(3, fold.reprices().size)
        assertEquals(
            "three DISTINCT durable keys — nothing is deduped away: ${fold.effectKeys}",
            3,
            fold.effectKeys.toSet().size,
        )
        assertEquals(
            listOf(
                "log:DELIVERY_RECEIPT_REPRICE:t1:J1:r1",
                "log:DELIVERY_RECEIPT_REPRICE:t1:J1:r2",
                "log:DELIVERY_RECEIPT_REPRICE:t1:J1:r3",
            ),
            fold.effectKeys,
        )
        assertEquals(
            "the marker's last decision is X again",
            receipt,
            fold.region.lastClosedJobReceipt!!.lastDecidedPay,
        )
        assertEquals(3, fold.region.lastClosedJobReceipt!!.repriceRevision)
    }

    @Test
    fun `an IDENTICAL consecutive receipt is suppressed before it ever reaches a key`() {
        val fold = Fold(closedJobRegion())
            .step(postTaskObs(expanded(), 21_000L))
            .step(postTaskObs(expanded(), 22_000L))
        assertEquals(1, fold.reprices().size)
        assertEquals(1, fold.region.lastClosedJobReceipt!!.repriceRevision)
    }

    @Test
    fun `the decision handoff lives exactly one transition`() {
        val fold = Fold(closedJobRegion()).step(postTaskObs(expanded(), 21_000L))
        assertNotNull("set on the deciding step", fold.region.pendingReceiptReprice)
        assertEquals(1, fold.reprices().size)

        fold.step(idleObs(21_500L))
        assertNull("cleared at the top of the very next step", fold.region.pendingReceiptReprice)
        assertEquals("and emitted exactly once", 1, fold.reprices().size)
    }

    @Test
    fun `a stale handoff restored from a snapshot emits nothing`() {
        val restored = closedJobRegion().copy(
            pendingReceiptReprice = PendingReceiptReprice(
                jobId = "J1", parsedPay = receipt, shares = mapOf("t1" to 16.70),
                decidedAt = 1_000L, revision = 1, sourceCaptureId = "cap-old",
            ),
        )
        val obs = idleObs(30_000L)
        val next = stepper.step(
            restored, FlowRegion(flow = Flow.PostTask), FlowRegion(flow = Flow.Idle), obs, policy,
        )
        assertNull("dropped by the top-of-step clear", next.pendingReceiptReprice)
        val events = effectMap
            .diff(appState(restored, Flow.PostTask), appState(next, Flow.Idle), obs)
            .filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a RESTORED marker plus a restored acceptance behaves exactly as a live one`() {
        // Crash recovery rebuilds both facts from the snapshot; neither is re-derived from live
        // objects, so a restored region must answer the ownership question identically.
        val noAccept = Fold(closedJobRegion()).step(postTaskObs(expanded(), 21_000L))
        assertEquals("restored, no acceptance → re-prices", 1, noAccept.reprices().size)

        val withAccept = Fold(closedJobRegion(acceptResolvedAt = 9_500L))
            .step(postTaskObs(expanded(), 21_000L))
        assertEquals("restored, an acceptance since the receipt → refuses", 0, withAccept.reprices().size)

        val staleAccept = Fold(closedJobRegion(acceptResolvedAt = 8_000L))
            .step(postTaskObs(expanded(), 21_000L))
        assertEquals("an acceptance from BEFORE the receipt is not this receipt's", 1, staleAccept.reprices().size)
    }

    @Test
    fun `a FOREIGN platform's receipt never re-prices this region's drops`() {
        // `StateMachine.stepPlatforms` routes an observation to its OWN platform's region only, so the
        // decision — which lives in the stepper — is never even offered a foreign frame.
        val machine = StateMachine(
            flowStepper = FlowRegionStepper(),
            platformStepper = stepper,
            crossPlatformStepper = CrossPlatformRegionStepper(),
            transitionPolicy = policy,
            effectMap = effectMap,
        )
        val state = appState(closedJobRegion(), Flow.PostTask)
        val uberReceipt = Observation.Screen(
            timestamp = 21_000L, captureId = "cap-uber", ruleId = "uber.screen.delivery_summary",
            metadata = ReplayMetadata.EMPTY, flow = Flow.PostTask, modeHint = Mode.Online,
            parsed = expanded(),
        )
        assertEquals("the fixture really is a foreign frame", Platform.Uber, uberReceipt.platform)

        val transition = machine.step(state, uberReceipt)

        assertNull(
            "the DoorDash region was never stepped, so it decided nothing",
            transition.newState.regions.platforms[Platform.DoorDash]?.pendingReceiptReprice,
        )
        assertTrue(
            transition.effects.filterIsInstance<AppEffect.LogEvent>()
                .none { it.event.type == AppEventType.DELIVERY_RECEIPT_REPRICE },
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
        assertEquals("…and its ownership anchor is when the receipt appeared", 10_000L, mark.receiptSeenAt)
        assertNull("no decision has been made from it yet", mark.lastDecidedPay)
    }
}
