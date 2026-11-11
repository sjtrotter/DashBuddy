package cloud.trotter.log.gig.core.state

import cloud.trotter.log.gig.domain.capture.ReplayMetadata
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.offer.OfferKind
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.order.OrderType
import cloud.trotter.log.gig.domain.model.order.ParsedOrder
import cloud.trotter.log.gig.domain.pipeline.Observation
import cloud.trotter.log.gig.domain.pipeline.ObservationPayload
import cloud.trotter.log.gig.domain.pipeline.TimeoutType
import cloud.trotter.log.gig.domain.state.AppState
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.FlowRegion
import cloud.trotter.log.gig.domain.state.Mode
import cloud.trotter.log.gig.domain.state.OfferIntent
import cloud.trotter.log.gig.domain.state.OfferSurface
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.Regions
import cloud.trotter.log.gig.domain.state.Session
import cloud.trotter.log.gig.domain.state.TaskPhase
import cloud.trotter.log.gig.domain.state.TaskSubFlow

/**
 * #1119 S2: synthetic frames and a `StateMachine` driver for the offer-pool tests. Invented store names only
 * (the `ReceiptStrictFixtures` style): the card names `Store A (Loop 410)`, the pickup `Store A #55`, one chain.
 */
internal object PF {
    val platform = Platform.DoorDash
    const val CARD_STORE = RS.STORE_HINT
    const val PICKUP_STORE = RS.STORE
    val OUTCOMES = setOf(
        AppEventType.OFFER_ACCEPTED, AppEventType.OFFER_DECLINED, AppEventType.OFFER_NOT_TAKEN, AppEventType.OFFER_TIMEOUT,
    )

    fun machine(policy: TransitionPolicy = TransitionPolicy()) = StateMachine(
        FlowRegionStepper(), PlatformRegionStepper(), CrossPlatformRegionStepper(), policy, EffectMap(),
    )

    /** An online shift, nothing on screen, R0 on Idle. */
    fun online(region: PlatformRegion = PlatformRegion(platform, mode = Mode.Online, session = Session("S1", startedAt = 0L),
        lastActedFlow = Flow.Idle)) = AppState(
        regions = Regions(flow = FlowRegion(flow = Flow.Idle, activePlatform = platform), platforms = mapOf(platform to region)),
        stateSchema = AppState.STATE_SCHEMA,
    )

    private fun order(store: String, i: Int = 0) = ParsedOrder(
        orderIndex = i, orderType = OrderType.PICKUP, storeName = store, itemCount = 1, isItemCountEstimated = false,
        badges = emptySet(),
    )

    fun parsedOffer(
        hash: String,
        stores: List<String> = listOf(CARD_STORE),
        scope: OfferScope? = OfferScope.STANDALONE,
        countdown: Int? = null,
        kind: OfferKind? = null,
        key: String? = null,
        pay: Double = 9.0,
    ) = ParsedOffer(
        offerHash = hash, presentationKey = key, payAmount = pay, distanceMiles = 4.0, timeToCompleteMinutes = 20L,
        dropoffCount = stores.size, orders = stores.mapIndexed { i, s -> order(s, i) }, initialCountdownSeconds = countdown,
        offerKind = kind, offerScope = scope,
    )

    fun card(
        at: Long,
        hash: String,
        stores: List<String> = listOf(CARD_STORE),
        scope: OfferScope? = OfferScope.STANDALONE,
        countdown: Int? = null,
        kind: OfferKind? = null,
        key: String? = null,
        pay: Double = 9.0,
    ) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.offer_popup", metadata = ReplayMetadata.EMPTY,
        flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(parsedOffer = parsedOffer(hash, stores, scope, countdown, kind, key, pay)),
    )

    /** The confirm-decline sheet: `offer:presented`, no parse, `offerSurface: decline_confirm`. */
    fun sheet(at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.offer_popup_confirm_decline",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online, parsed = ParsedFields.None,
        offerSurface = OfferSurface.DECLINE_CONFIRM,
    )

    fun idle(at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.idle_map", metadata = ReplayMetadata.EMPTY,
        flow = Flow.Idle, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    fun pickup(at: Long, store: String? = PICKUP_STORE, flow: Flow = Flow.TaskPickupNavigation) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.pickup_navigation", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(
            phase = TaskPhase.PICKUP,
            subFlow = if (flow == Flow.TaskPickupArrived) TaskSubFlow.ARRIVED else TaskSubFlow.NAVIGATION,
            storeName = store,
        ),
    )

    fun dropoff(at: Long, customer: String = "cust-1", flow: Flow = Flow.TaskDropoffNavigation) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.dropoff_navigation", metadata = ReplayMetadata.EMPTY,
        flow = flow, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(
            phase = TaskPhase.DROPOFF,
            subFlow = if (flow == Flow.TaskDropoffArrived) TaskSubFlow.ARRIVED else TaskSubFlow.NAVIGATION,
            customerNameHash = customer, customerAddressHash = "addr-$customer",
        ),
    )

    fun taskActive(at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.task_active", metadata = ReplayMetadata.EMPTY,
        flow = Flow.TaskActive, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    fun click(at: Long, intent: String) = Observation.Click(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.click.offer", metadata = ReplayMetadata.EMPTY,
        flow = null, modeHint = null, parsed = ParsedFields.ClickFields(intent = intent),
    )

    fun accept(at: Long) = click(at, OfferIntent.ACCEPT)
    fun decline(at: Long) = click(at, OfferIntent.DECLINE)

    fun expiry(at: Long, hash: String, instanceId: String?) = Observation.Timeout(
        timestamp = at, type = TimeoutType.OFFER_EXPIRY, targetPlatform = platform,
        payload = ObservationPayload.OfferExpiry(hash, instanceId),
    )

    fun evalLanded(at: Long, hash: String, evaluation: cloud.trotter.log.gig.domain.evaluation.OfferEvaluation) =
        Observation.Loopback(
            timestamp = at, effect = Observation.Loopback.EFFECT_OFFER_EVALUATED, targetPlatform = platform,
            payload = ObservationPayload.EvaluationResult(action = "evaluated", offerHash = hash, evaluation = evaluation),
        )

    /** The session end: the dash summary arms the authoritative grace, its wake commits it. */
    fun summary(at: Long) = Observation.Screen(
        timestamp = at, captureId = "cap-$at", ruleId = "doordash.screen.dash_summary", metadata = ReplayMetadata.EMPTY,
        flow = Flow.SessionEnded, modeHint = Mode.Offline, parsed = ParsedFields.None,
    )

    fun instanceOf(at: Long) = "${platform.wire}:$at"
}

/**
 * A fold of observations through the real `StateMachine` and `EffectMap`, with a fake `effects_fired` table (a
 * keyed `LogEvent` fires once, the engine's durable idempotency).
 */
internal class PoolDriver(start: AppState = PF.online(), policy: TransitionPolicy = TransitionPolicy()) {
    private val machine = PF.machine(policy)
    var state: AppState = start
    val events = mutableListOf<AppEvent>()
    val allEffects = mutableListOf<AppEffect>()
    var last: List<AppEffect> = emptyList()
    private val fired = mutableSetOf<String>()

    val region: PlatformRegion get() = state.regions.platforms.getValue(PF.platform)

    fun step(vararg obs: Observation): PoolDriver {
        for (o in obs) {
            val t = machine.step(state, o)
            state = t.newState
            last = t.effects
            allEffects += t.effects
            events += t.effects.filterIsInstance<AppEffect.LogEvent>().filter { fired.add(it.effectKey) }.map { it.event }
        }
        return this
    }

    /** Ends the shift: the summary, then a frame past the authoritative grace commits it. */
    fun endShift(at: Long): PoolDriver = step(PF.summary(at), PF.idle(at + 60_000).copy(modeHint = Mode.Offline))

    fun outcomes(): List<AppEvent> = events.filter { it.type in PF.OUTCOMES }
    fun outcomePayloads(): List<OfferPayload> = outcomes().map { it.payload as OfferPayload }
    fun outcomeOf(instance: String): List<AppEvent> =
        outcomes().filter { (it.payload as OfferPayload).offerInstanceId == instance }
}
