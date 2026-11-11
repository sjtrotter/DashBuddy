package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.core.state.model.RegionDecisions
import cloud.trotter.log.gig.core.state.model.StepDecisions
import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryReceiptRepricePayload
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptObservedPayload
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.AcceptedOfferEconomics
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.CrossPlatformRegion
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Job
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase

/**
 * #1598 shared fixtures for the strict-coverage tests. Invented store names only. A **proven fixture** is a live job
 * holding one accepted offer whose card showed one drop (`Store A (Loop 410)`), and one accountable dropoff whose
 * store reads `Store A #55` — the same chain through `StoreKeys.normalizedChain`.
 */
internal object RS {
    val stepper = PlatformRegionStepper()
    val policy = TransitionPolicy()
    val effectMap = EffectMap()

    const val STORE_HINT = "Store A (Loop 410)"
    const val STORE = "Store A #55"

    fun pay(base: Double, tip: Double, store: String = STORE) = ParsedPay(
        appPayComponents = listOf(ParsedPayItem("Base Pay", base)),
        customerTips = listOf(ParsedPayItem(store, tip)),
    )

    fun collapsed(total: Double) = ParsedFields.PostTaskFields(totalPay = total, parsedPay = null, isExpanded = false)

    fun expanded(pay: ParsedPay) = ParsedFields.PostTaskFields(totalPay = pay.total, parsedPay = pay, isExpanded = true)

    fun offer(
        hash: String = "o1",
        payAmount: Double? = 9.0,
        hints: List<String> = listOf(STORE_HINT),
        count: Int? = 1,
    ) = AcceptedOfferEconomics(
        offerHash = hash, payAmount = payAmount, storeHints = hints, cardDropoffCount = count, acceptedAt = 100L,
    )

    fun drop(
        id: String,
        jobId: String = "J1",
        store: String? = STORE,
        completedAt: Long? = null,
        arrivedAt: Long? = 3_500L,
        identity: Boolean = true,
    ) = Task(
        taskId = id, jobId = jobId, phase = TaskPhase.DROPOFF, storeName = store,
        customerNameHash = if (identity) "cust-$id" else null,
        startedAt = 3_000L, arrivedAt = arrivedAt, completedAt = completedAt,
    )

    fun job(
        tasks: List<Task>,
        offers: List<AcceptedOfferEconomics> = listOf(offer()),
        jobId: String = "J1",
    ) = Job(
        jobId = jobId, offerStoreHint = offers.flatMap { it.storeHints }, parentOfferHash = offers.firstOrNull()?.offerHash,
        acceptedOffers = offers, tasks = tasks, startedAt = 200L,
    )

    /** A live job on its dropoff [active], about to see the receipt. */
    fun live(
        active: Task = drop("t1"),
        recent: List<Task> = emptyList(),
        offers: List<AcceptedOfferEconomics> = listOf(offer()),
        jobTasks: List<Task> = recent + active,
    ) = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("S1", startedAt = 100L),
        activeJob = job(jobTasks, offers, active.jobId),
        activeTask = active,
        recentTasks = recent,
        lastActedFlow = Flow.TaskDropoffArrived,
    )

    fun postTask(parsed: ParsedFields, at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.delivery_summary",
        metadata = ReplayMetadata.EMPTY, flow = Flow.PostTask, modeHint = Mode.Online, parsed = parsed,
    )

    fun screen(flow: Flow, at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.any",
        metadata = ReplayMetadata.EMPTY, flow = flow, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    fun offerScreen(at: Long, hash: String, store: String = "Store B") = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.offer_popup",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(
                offerHash = hash, payAmount = 12.0, distanceMiles = 5.0, dropoffCount = 1,
                orders = listOf(
                    ParsedOrder(
                        orderIndex = 0, orderType = OrderType.PICKUP, storeName = store,
                        itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
                    ),
                ),
            ),
        ),
    )

    fun timeout(at: Long, type: TimeoutType) = Observation.Timeout(
        timestamp = at, type = type, targetPlatform = Platform.DoorDash,
    )

    fun appState(region: PlatformRegion, flow: Flow) = AppState(
        regions = Regions(
            flow = FlowRegion(flow = flow),
            platforms = mapOf(Platform.DoorDash to region),
            crossPlatform = CrossPlatformRegion(),
        ),
    )
}

/**
 * A fold of real observations through the real stepper and `EffectMap`, tracking R0 as `StateMachine` does (a
 * flow-less observation leaves the flow where it was), with a fake `effects_fired` table.
 */
internal class ReceiptFold(start: PlatformRegion, private var flow: Flow = start.lastActedFlow ?: Flow.Idle) {
    var region: PlatformRegion = start
    var decisions: RegionDecisions = RegionDecisions.NONE
    val events = mutableListOf<AppEvent>()
    val keys = mutableListOf<Pair<AppEventType, String?>>()
    private val fired = mutableSetOf<String>()

    fun step(obs: Observation): ReceiptFold {
        val prevRegion = region
        val prevFlow = flow
        val nextFlow = (obs as? Observation.FlowObservation)?.flow ?: prevFlow
        val stepped = RS.stepper.stepWithDecisions(
            prevRegion, FlowRegion(flow = prevFlow), FlowRegion(flow = nextFlow), obs, RS.policy,
        )
        region = stepped.region
        decisions = stepped.decisions
        val logged = RS.effectMap
            .diff(
                RS.appState(prevRegion, prevFlow), RS.appState(region, nextFlow), obs,
                StepDecisions(mapOf(Platform.DoorDash to decisions)),
            )
            .filterIsInstance<AppEffect.LogEvent>()
            // The engine's durable idempotency: a key already fired is dropped.
            .filter { it.effectKeyOverride == null || fired.add(it.effectKeyOverride) }
        events += logged.map { it.event }
        keys += logged.map { it.event.type to it.effectKeyOverride }
        flow = nextFlow
        return this
    }

    fun completions(): List<DeliveryPayload> =
        events.filter { it.type == AppEventType.DELIVERY_COMPLETED }.map { it.payload as DeliveryPayload }

    fun reprices(): List<DeliveryReceiptRepricePayload> =
        events.filter { it.type == AppEventType.DELIVERY_RECEIPT_REPRICE }.map { it.payload as DeliveryReceiptRepricePayload }

    fun evidence(): List<ReceiptObservedPayload> =
        events.filter { it.type == AppEventType.RECEIPT_OBSERVED }.map { it.payload as ReceiptObservedPayload }

    fun evidenceKeys(): List<String?> = keys.filter { it.first == AppEventType.RECEIPT_OBSERVED }.map { it.second }

    /**
     * Receipt money that landed per drop: the last re-price share, else the completion's share, else its bare total.
     * The estimate (`offerPayShare`) is not receipt money and is excluded.
     */
    fun receiptMoney(): Map<String, Double> {
        val money = mutableMapOf<String, Double>()
        completions().forEach { c -> (c.dropRealizedPay ?: c.totalPay)?.let { money[c.taskId] = it } }
        reprices().forEach { money[it.taskId] = it.dropRealizedPay }
        return money
    }
}
