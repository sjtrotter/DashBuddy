package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.analytics.RecordFolds
import cloud.trotter.log.gig.domain.analytics.SessionFoldContext
import cloud.trotter.log.gig.domain.analytics.SessionReportRule
import cloud.trotter.log.gig.domain.analytics.ShiftEarnings
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.evaluation.OfferAction
import cloud.trotter.log.gig.domain.evaluation.OfferEvaluation
import cloud.trotter.log.gig.domain.evaluation.OfferQuality
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.DestructiveKind
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.OfferIntent
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * #1458 — characterization: a stacked or add-on job's quote counts once the WHOLE job is judged
 * delivered, at its completion edge (T1, a dropoff retire that leaves the job physically complete; T2,
 * an accept over a complete job), never drop by drop; a job that cannot be judged complete keeps
 * today's close (the next accept or the shift end). Nothing here changes production behaviour — each
 * test pins what master already does on one of the October job shapes (design
 * `2026-11-01-1458/field-evidence.md`, J1–J6), with synthetic quotes, synthetic hashes and no field
 * text.
 *
 * Each fixture drives the REAL [StateMachine] (classifier output is synthesized as `Observation`s, as
 * `SessionReplay.reduceMixed`'s `RawInput` does in `:app`). Accepted offers are seeded as
 * accepted-pending-consumption survivors on the region, the shape the offer lifecycle leaves them in
 * (#438 B3), and consumed by the production accept path on the next task frame. Every emitted
 * `LogEvent` is de-duplicated by its effect key (the engine's `effects_fired`) and folded through the
 * pure [RecordFolds]; the headline input is `ShiftEarnings.live(null, recordedPay)` over the folded
 * rows — the running total is left out so only our recorded pay moves it.
 */
class StackedJobCloseTimingTest {

    private val platform = Platform.DoorDash
    private val machine = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), TransitionPolicy(), EffectMap(),
    )

    /** Every `StateMachine` line, so a test can read the #1458 left-open reason and the #997 WARN. */
    private val logged = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            if (tag == "StateMachine") logged += message
        }
    }
    private val leftOpen: List<String> get() = logged.filter { it.startsWith("#1458") }

    @Before fun plant() = Timber.plant(tree)

    @After fun uproot() = Timber.uproot(tree)

    // =====================================================================================
    // The drive: one dash, stepped frame by frame through the real machine
    // =====================================================================================

    private inner class Dash {
        var state = AppState(regions = Regions(
            flow = FlowRegion(flow = Flow.Idle, activePlatform = platform),
            platforms = mapOf(platform to PlatformRegion(
                platform = platform, mode = Mode.Online, session = Session("dash", startedAt = 0L),
            )),
        ))
            private set
        private val fired = HashSet<String>()

        /** Every event the engine would have written, in order, once per effect key. */
        val events = mutableListOf<AppEvent>()

        val region: PlatformRegion get() = state.regions.platforms.getValue(platform)

        /** Step [obs]; returns the DELIVERY_COMPLETED payloads this step wrote. */
        fun step(obs: Observation): List<DeliveryPayload> {
            val t = machine.step(state, obs)
            state = t.newState
            val written = t.effects.filterIsInstance<AppEffect.LogEvent>().filter { fired.add(it.effectKey) }.map { it.event }
            events += written
            return written.completions()
        }

        /** Seed an accepted offer survivor; the next task frame consumes it through the production accept path. */
        fun accepted(offer: PendingOffer) {
            state = state.copy(regions = state.regions.copy(
                platforms = state.regions.platforms + (platform to region.copy(pendingOffers = region.pendingOffers + offer)),
            ))
        }

        /** Fire the standing destructive grace's own wake at its deadline (the engine's timer). */
        fun commitGrace(): List<DeliveryPayload> {
            val pending = requireNotNull(region.pendingDestructive) { "no grace is armed" }
            return step(Observation.Timeout(
                timestamp = pending.deadline, type = TimeoutType.GRACE_COMMIT, targetPlatform = platform,
                payload = ObservationPayload.GraceWake(pending.wakeId.value),
            ))
        }

        /** The headline input after folding everything written so far: null until a delivery is priced. */
        fun headline(): Double? {
            var context: SessionFoldContext? = null
            val rows = LinkedHashMap<Long, Double?>()
            val rowKeys = HashMap<Pair<String, String>, Long>()
            events.forEachIndexed { i, event ->
                val outcome = RecordFolds.foldEvent(SequencedAppEvent(i + 1L, event), context, currentCostPerMile = 0.5)
                context = outcome.context ?: context
                outcome.delivery?.let {
                    rows[it.eventSequenceId] = it.realizedPay
                    rowKeys[it.jobId to it.taskId] = it.eventSequenceId
                }
                // The projector applies a re-price by (jobId, taskId); a missing row is a counted skip.
                outcome.receiptReprice?.let { r -> rowKeys[r.jobId to r.taskId]?.let { rows[it] = r.realizedPay } }
            }
            return ShiftEarnings.live(running = null, recordedPay = SessionReportRule.recordedPayOf(rows.values))
        }

        fun completionsSoFar(): List<DeliveryPayload> = events.completions()

        /**
         * J1's third accept: an accepted quote the job carries with NO placeholder of its own (the field
         * job logged three accepts for two accounted drops). Seeded on the live job; nothing mints for it.
         */
        fun orphanAccept(economics: AcceptedOfferEconomics) {
            val job = requireNotNull(region.activeJob)
            state = state.copy(regions = state.regions.copy(
                platforms = state.regions.platforms +
                    (platform to region.copy(activeJob = job.copy(acceptedOffers = job.acceptedOffers + economics))),
            ))
        }
    }

    private fun List<AppEvent>.completions(): List<DeliveryPayload> =
        filter { it.type == AppEventType.DELIVERY_COMPLETED }.map { it.payload as DeliveryPayload }

    // ---- synthetic recognizer output ----

    private fun screen(at: Long, flow: Flow?, parsed: ParsedFields = ParsedFields.None) = Observation.Screen(
        timestamp = at, captureId = null, ruleId = "${platform.wire}.screen.synthetic",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = if (flow == null) null else Mode.Online,
        parsed = parsed,
    )

    private fun pickupNav(at: Long, store: String, customer: String? = null) = screen(at, Flow.TaskPickupNavigation,
        ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = store,
            customerNameHash = customer))

    private fun pickupArrived(at: Long, store: String, customer: String? = null) = screen(at, Flow.TaskPickupArrived,
        ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.ARRIVED, storeName = store,
            customerNameHash = customer))

    /** `dropoff_pre_arrival` / `dropoff_navigation`: navigating to [customer]. */
    private fun dropNav(at: Long, customer: String, store: String? = null) = screen(at, Flow.TaskDropoffNavigation,
        ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.NAVIGATION, storeName = store,
            customerNameHash = customer, customerAddressHash = "$customer-address"))

    /** `dropoff_photo`: arrived at [customer]'s door. */
    private fun dropPhoto(at: Long, customer: String, store: String? = null) = screen(at, Flow.TaskDropoffArrived,
        ParsedFields.TaskFields(phase = TaskPhase.DROPOFF, subFlow = TaskSubFlow.ARRIVED, storeName = store,
            customerNameHash = customer, customerAddressHash = "$customer-address"))

    /** `dash_along_the_way` / `waiting_for_offer`: the idle map after a drop. */
    private fun idle(at: Long) = screen(at, Flow.Idle)

    /** `dropoff_multi_order_confirm`: recognize-only (no flow), so it moves nothing. */
    private fun multiOrderConfirm(at: Long) = screen(at, flow = null)

    private fun offerCard(at: Long, hash: String, pay: Double, store: String) = screen(at, Flow.OfferPresented,
        ParsedFields.OfferFields(parsedOffer = parsedOffer(hash, pay, listOf(store), OfferScope.ADD_ON)))

    private fun declineTap(at: Long) = Observation.Click(
        timestamp = at, captureId = null, ruleId = "${platform.wire}.click.${OfferIntent.DECLINE}",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = OfferIntent.DECLINE), target = OfferIntent.DECLINE,
        screenTarget = "offer_popup",
    )

    private fun receipt(at: Long, pay: ParsedPay) = screen(at, Flow.PostTask,
        ParsedFields.PostTaskFields(totalPay = pay.total, parsedPay = pay, isExpanded = true))

    private fun dashSummary(at: Long) = screen(at, Flow.SessionEnded, ParsedFields.SessionEndedFields())

    // ---- synthetic offers ----

    private fun parsedOffer(
        hash: String,
        pay: Double,
        stores: List<String>,
        scope: OfferScope,
        basis: OfferQuoteBasis = OfferQuoteBasis.TOTAL,
        stopCount: Int? = null,
    ) = ParsedOffer(
        offerHash = hash, offerScope = scope, quoteBasis = basis, stopCount = stopCount,
        payAmount = pay, distanceMiles = 4.0, timeToCompleteMinutes = 25L,
        orders = stores.mapIndexed { i, s -> ParsedOrder(i, OrderType.PICKUP, s, 1, false, emptySet()) },
    )

    private fun acceptedOffer(
        hash: String,
        pay: Double,
        stores: List<String>,
        at: Long,
        scope: OfferScope = OfferScope.STANDALONE,
        basis: OfferQuoteBasis = OfferQuoteBasis.TOTAL,
        stopCount: Int? = null,
    ) = PendingOffer(
        offerHash = hash,
        offerFields = ParsedFields.OfferFields(parsedOffer = parsedOffer(hash, pay, stores, scope, basis, stopCount)),
        presentedAt = at - 5_000L,
        evaluation = OfferEvaluation(
            action = OfferAction.ACCEPT, score = 70.0, qualityLevel = OfferQuality.GOOD,
            payAmount = pay, fuelCostEstimate = 0.5, netPayAmount = pay - 1.0, distanceMiles = 4.0,
            dollarsPerMile = pay / 4.0, dollarsPerHour = 20.0, estimatedTimeMinutes = 25.0, itemCount = 1.0,
            merchantName = stores.first(), quoteBasis = basis,
        ),
        returnFlow = Flow.Idle,
        lastClickIntent = OfferIntent.ACCEPT,
        acceptClickAt = at - 1_000L,
        acceptedAt = at - 1_000L,
    )

    private fun cents(v: Double?): Long = Math.round((v ?: 0.0) * 100.0)

    private fun Iterable<DeliveryPayload>.priced(): Double = sumOf { it.dropRealizedPay ?: it.offerPayShare ?: 0.0 }

    private fun assertHeadline(message: String, expected: Double?, dash: Dash) {
        val actual = dash.headline()
        if (expected == null) assertNull("$message: no priced row yet", actual)
        else assertEquals(message, cents(expected), cents(requireNotNull(actual) { "$message: headline is null" }))
    }

    /** A plain accept → one pickup at [store] → the dash is ready to drive drops. */
    private fun Dash.acceptAndPickUp(hash: String, pay: Double, stores: List<String>, at: Long) {
        accepted(acceptedOffer(hash, pay, stores, at))
        assertTrue(step(pickupNav(at, stores.first())).isEmpty())
        assertNotNull("the accept minted a job", region.activeJob)
        assertTrue(step(pickupArrived(at + 60_000L, stores.first())).isEmpty())
    }

    // =====================================================================================
    // J2 — $20 for two drops: D1 photo → D2 pre-arrival (displacement) → D2 photo → idle → commit
    // =====================================================================================

    @Test
    fun `J2 a displaced first drop credits nothing until the last drop's retire closes the job`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 20.0, listOf("Store A", "Store A"), at = 10_000L)
        assertTrue(dash.step(dropNav(100_000L, "cust-1")).isEmpty())
        assertTrue(dash.step(dropPhoto(200_000L, "cust-1")).isEmpty())
        assertHeadline("D1 at the door", null, dash)
        // D2's pre-arrival displaces D1 (stamped complete, no retire grace).
        assertTrue("no row at the displacement", dash.step(dropNav(202_000L, "cust-2")).isEmpty())
        assertHeadline("D1 displaced", null, dash)
        assertTrue(dash.step(dropPhoto(500_000L, "cust-2")).isEmpty())
        assertTrue(dash.step(idle(502_000L)).isEmpty())
        assertEquals(DestructiveKind.TASK_RETIRE, dash.region.pendingDestructive?.kind)
        assertHeadline("D2 retiring, inside its grace", null, dash)

        val rows = dash.commitGrace()
        assertNull("T1 closed the job", dash.region.activeJob)
        assertEquals("one row per drop, on the close step", 2, rows.size)
        assertEquals("the shares sum to the quote", 2000L, cents(rows.priced()))
        assertHeadline("the whole job", 20.0, dash)
        assertTrue("a judged job logs no left-open line", leftOpen.isEmpty())
    }

    // =====================================================================================
    // J1 — $20 for two drops + an orphan $5 accept. D1: photo → dash_along_the_way → retire commit.
    // =====================================================================================

    @Test
    fun `J1 an intermediate retire leaves the job open and the last drop's retire credits A's quote`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 20.0, listOf("Store A", "Store A"), at = 10_000L)
        dash.orphanAccept(AcceptedOfferEconomics(
            offerHash = "offer-b", payAmount = 5.0, storeHints = listOf("Store B"), acceptedAt = 50_000L,
        ))
        dash.step(dropNav(100_000L, "cust-1"))
        dash.step(dropPhoto(200_000L, "cust-1"))
        assertTrue(dash.step(idle(200_550L)).isEmpty())
        assertTrue("D1's retire commits, the job stays open", dash.commitGrace().isEmpty())
        assertNotNull(dash.region.activeJob)
        assertHeadline("after D1", null, dash)
        assertEquals("the left-open line names the outstanding drop", 1, leftOpen.size)
        assertTrue(leftOpen.single(), leftOpen.single().contains("outstanding=1 unevidenced=0 finished=1"))
        assertTrue(leftOpen.single(), leftOpen.single().contains("armedFromOffer=false"))

        dash.step(dropNav(1_700_000L, "cust-2"))
        dash.step(dropPhoto(1_800_000L, "cust-2"))
        dash.step(idle(1_800_600L))
        val rows = dash.commitGrace()
        assertNull("T1 closed the job", dash.region.activeJob)
        assertEquals(2, rows.size)
        assertEquals("A's quote over its two drops; B's \$5 has no drop", 2000L, cents(rows.priced()))
        assertHeadline("the whole job", 20.0, dash)
        assertTrue("the orphan accept is stated (#997)", logged.any { it.startsWith("#997 offer pay unattributed") })
    }

    // =====================================================================================
    // J3 — $13 for three drops. D1 and D2 displaced, multi-order confirm frames between; D3 idle commit.
    // =====================================================================================

    @Test
    fun `J3 two displaced drops credit nothing and the third drop's retire credits the equal split`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 13.0, listOf("Store A", "Store A", "Store A"), at = 10_000L)
        dash.step(dropNav(100_000L, "cust-1"))
        dash.step(dropPhoto(200_000L, "cust-1"))
        assertTrue(dash.step(dropNav(202_000L, "cust-2")).isEmpty())
        assertTrue(dash.step(multiOrderConfirm(202_270L)).isEmpty())
        assertTrue(dash.step(dropNav(204_450L, "cust-2")).isEmpty())
        assertHeadline("D1 displaced", null, dash)
        dash.step(dropPhoto(600_000L, "cust-2"))
        assertTrue(dash.step(dropNav(601_840L, "cust-3")).isEmpty())
        assertHeadline("D2 displaced", null, dash)
        dash.step(dropPhoto(900_000L, "cust-3"))
        dash.step(idle(902_000L))
        assertTrue("nothing before the close", dash.completionsSoFar().isEmpty())

        val rows = dash.commitGrace()
        assertEquals(3, rows.size)
        assertEquals(1300L, cents(rows.priced()))
        rows.forEach { assertTrue("an equal split, within a cent", kotlin.math.abs(cents(it.offerPayShare) - 433L) <= 1L) }
        assertHeadline("the whole job", 13.0, dash)
    }

    // =====================================================================================
    // J4 — D1 → the job's next pickup (an add-on's) → D2 with an itemized receipt
    // =====================================================================================

    @Test
    fun `J4 a drop displaced by an add-on pickup waits for the receipt, which is split over both drops`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 8.0, listOf("Store A"), at = 10_000L)
        dash.step(dropNav(100_000L, "cust-1", store = "Store A"))
        dash.step(dropPhoto(200_000L, "cust-1", store = "Store A"))
        dash.accepted(acceptedOffer("offer-b", 5.0, listOf("Store B"), at = 200_890L, scope = OfferScope.ADD_ON))
        assertTrue("the add-on pickup displaces D1, no row", dash.step(pickupNav(200_890L, "Store B", "cust-2")).isEmpty())
        assertEquals("the add-on joined the job", 2, dash.region.activeJob!!.acceptedOffers.size)
        assertHeadline("D1 displaced by the pickup", null, dash)
        dash.step(pickupArrived(203_740L, "Store B", "cust-2"))
        assertHeadline("still nothing through the pickup", null, dash)
        dash.step(dropNav(1_000_000L, "cust-2", store = "Store B"))
        dash.step(dropPhoto(1_100_000L, "cust-2", store = "Store B"))
        assertTrue(dash.completionsSoFar().isEmpty())

        val pay = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 6.0)),
            customerTips = listOf(ParsedPayItem("Store A", 4.0), ParsedPayItem("Store B", 3.0)),
        )
        assertTrue("the receipt frame itself writes nothing", dash.step(receipt(1_100_500L, pay)).isEmpty())
        val rows = dash.step(idle(1_104_000L))
        assertNull("the PostTask exit closed the job", dash.region.activeJob)
        assertEquals("D2's exit row and D1's close-out row, on one step", 2, rows.size)
        assertEquals("the receipt, split over both drops", 1300L, rows.sumOf { cents(it.dropRealizedPay) })
        assertHeadline("the whole job", 13.0, dash)
    }

    // =====================================================================================
    // J5 — cannot judge: a dropoff placeholder never activates, so no completion edge fires
    // =====================================================================================

    @Test
    fun `J5 a job with a never-activated placeholder is not judged and its rows land at the shift end`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 12.0, listOf("Store A", "Store A", "Store A"), at = 10_000L)
        dash.step(dropNav(100_000L, "cust-1"))
        dash.step(dropPhoto(200_000L, "cust-1"))
        dash.step(idle(200_500L))
        assertTrue(dash.commitGrace().isEmpty())
        dash.step(dropNav(300_000L, "cust-2"))
        dash.step(dropPhoto(400_000L, "cust-2"))
        dash.step(idle(400_500L))
        assertTrue("the last seen drop's retire does not close the job", dash.commitGrace().isEmpty())
        assertNotNull(dash.region.activeJob)
        assertHeadline("both drops delivered, job not judged", null, dash)
        assertEquals(2, leftOpen.size)
        assertTrue(leftOpen.last(), leftOpen.last().contains("outstanding=1 unevidenced=0 finished=2"))
        assertTrue(leftOpen.last(), leftOpen.last().contains("coverage=PICKUP_DROPOFF_MISMATCH"))

        dash.step(dashSummary(2_000_000L))
        val rows = dash.commitGrace()
        assertNull("the shift ended", dash.region.session)
        assertEquals("the delivered drops land at the shift end", 2, rows.size)
        // Not proven complete, so the quote stays spread over all three owed orders: the never-seen
        // order's third rides no row (today's close, unchanged).
        rows.forEach { assertEquals("a third of the quote each", 400L, cents(it.offerPayShare)) }
        assertHeadline("the shift end's rows", rows.priced(), dash)
    }

    // =====================================================================================
    // J6 — $9 for two drops: D1 photo → pre ↔ multi-order confirm (×2) → nav; D2 commit
    // =====================================================================================

    @Test
    fun `J6 confirm frames between pre-arrival renders change nothing and D2's commit credits the quote`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 9.0, listOf("Store A", "Store A"), at = 10_000L)
        dash.step(dropNav(100_000L, "cust-1"))
        dash.step(dropPhoto(200_000L, "cust-1"))
        dash.step(dropNav(201_800L, "cust-2"))
        dash.step(multiOrderConfirm(202_300L))
        dash.step(dropNav(202_340L, "cust-2"))
        dash.step(multiOrderConfirm(202_430L))
        dash.step(dropNav(204_830L, "cust-2"))
        dash.step(dropNav(205_860L, "cust-2"))
        assertTrue(dash.completionsSoFar().isEmpty())
        assertHeadline("D1 displaced", null, dash)
        dash.step(dropPhoto(800_000L, "cust-2"))
        dash.step(idle(801_000L))
        val rows = dash.commitGrace()
        assertEquals(2, rows.size)
        assertEquals(900L, cents(rows.priced()))
        assertHeadline("the whole job", 9.0, dash)
    }

    // =====================================================================================
    // Add-on — $10 for two drops; an incremental $3 add-on accepted after D1 adds one drop
    // =====================================================================================

    @Test
    fun `an add-on folded into the job waits for its own drop, then A's quote and B's quote land together`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 10.0, listOf("Store A", "Store A"), at = 10_000L)
        dash.step(dropNav(100_000L, "cust-1", store = "Store A"))
        dash.step(dropPhoto(200_000L, "cust-1", store = "Store A"))
        dash.step(idle(200_500L))
        assertTrue(dash.commitGrace().isEmpty())
        assertHeadline("D1", null, dash)

        dash.accepted(acceptedOffer(
            "offer-b", 3.0, listOf("Store B"), at = 300_000L,
            scope = OfferScope.ADD_ON, basis = OfferQuoteBasis.INCREMENTAL, stopCount = 2,
        ))
        dash.step(pickupNav(300_000L, "Store B", "cust-3"))
        val job = requireNotNull(dash.region.activeJob)
        assertEquals("the add-on joined the job", listOf("offer-a", "offer-b"), job.acceptedOffers.map { it.offerHash })
        assertEquals("and added one drop", 3, job.tasks.count { it.phase == TaskPhase.DROPOFF })
        dash.step(pickupArrived(400_000L, "Store B", "cust-3"))
        dash.step(dropNav(500_000L, "cust-2", store = "Store A"))
        dash.step(dropPhoto(600_000L, "cust-2", store = "Store A"))
        dash.step(idle(600_500L))
        assertTrue("A's last drop does not close a job that owes the add-on's drop", dash.commitGrace().isEmpty())
        assertHeadline("D2", null, dash)

        dash.step(dropNav(700_000L, "cust-3", store = "Store B"))
        dash.step(dropPhoto(800_000L, "cust-3", store = "Store B"))
        dash.step(idle(800_500L))
        val rows = dash.commitGrace()
        assertNull("T1 closed the job", dash.region.activeJob)
        assertEquals(3, rows.size)
        assertEquals("B's quote on its own drop", 300L, cents(rows.single { it.customerHash == "cust-3" }.offerPayShare))
        assertEquals("A's quote over its drops", 1000L, cents(rows.filter { it.customerHash != "cust-3" }.priced()))
        assertHeadline("both quotes", 13.0, dash)
    }

    // =====================================================================================
    // Unassigned — $12 for two drops; B unassigned via help, then A delivered
    // =====================================================================================

    @Test
    fun `an unassigned order never blocks the close and keeps its share off the delivered drop`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 12.0, listOf("Store A", "Store A"), at = 10_000L)
        dash.step(dropNav(100_000L, "cust-2"))
        assertTrue(dash.step(screen(150_000L, Flow.TaskUnassigned)).isEmpty())
        dash.step(dropNav(200_000L, "cust-1"))
        dash.step(dropPhoto(300_000L, "cust-1"))
        dash.step(idle(300_500L))
        val rows = dash.commitGrace()
        assertNull("T1 closed the job on the remaining drop", dash.region.activeJob)
        assertEquals("only the delivered drop mints", listOf("cust-1"), rows.map { it.customerHash })
        assertEquals("its share; the unassigned share rides no row", 600L, cents(rows.priced()))
        assertHeadline("the delivered share", 6.0, dash)
    }

    // =====================================================================================
    // Offer-card last retire — the last drop's retire is armed on an offer card, then the offer declined
    // =====================================================================================

    @Test
    fun `a last retire armed on an offer card is not judged until the next accept closes the job`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 14.0, listOf("Store A", "Store A"), at = 10_000L)
        dash.step(dropNav(100_000L, "cust-1"))
        dash.step(dropPhoto(200_000L, "cust-1"))
        dash.step(dropNav(201_000L, "cust-2"))
        dash.step(dropPhoto(500_000L, "cust-2"))
        dash.step(offerCard(500_500L, "offer-x", 4.0, "Store X"))
        val retire = requireNotNull(dash.region.pendingDestructive)
        assertEquals(DestructiveKind.TASK_RETIRE, retire.kind)
        assertEquals(Flow.OfferPresented, retire.armedFromFlow)
        assertTrue("the retire commits while the card is up; the job stays open", dash.commitGrace().isEmpty())
        assertNotNull(dash.region.activeJob)
        assertTrue(leftOpen.single(), leftOpen.single().contains("coverage=- armedFromOffer=true"))
        dash.step(declineTap(520_000L))
        dash.step(idle(520_500L))
        assertTrue(dash.completionsSoFar().isEmpty())
        assertHeadline("declined, nothing judged", null, dash)

        dash.accepted(acceptedOffer("offer-c", 6.0, listOf("Store C"), at = 900_000L))
        val rows = dash.step(pickupNav(900_000L, "Store C"))
        assertEquals("T2: the accept over the complete job closes it", 2, rows.size)
        assertEquals(1400L, cents(rows.priced()))
        assertEquals("and mints a fresh job for the new offer", listOf("offer-c"),
            dash.region.activeJob!!.acceptedOffers.map { it.offerHash })
        assertHeadline("the old job", 14.0, dash)
    }

    // =====================================================================================
    // Single — one drop, no receipt
    // =====================================================================================

    @Test
    fun `a single drop credits its quote at the retire commit, one grace after the idle frame`() {
        val dash = Dash()
        dash.acceptAndPickUp("offer-a", 7.5, listOf("Store A"), at = 10_000L)
        dash.step(dropNav(100_000L, "cust-1"))
        dash.step(dropPhoto(200_000L, "cust-1"))
        dash.step(idle(200_500L))
        assertEquals("the retire grace", 10_000L, dash.region.pendingDestructive!!.deadline - 200_500L)
        assertHeadline("inside the grace", null, dash)
        val rows = dash.commitGrace()
        assertEquals(1, rows.size)
        assertEquals(750L, cents(rows.priced()))
        assertHeadline("the quote", 7.5, dash)
    }
}
