package cloud.trotter.dashbuddy.core.state

import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.pipeline.ObservationPayload
import cloud.trotter.dashbuddy.domain.pipeline.TimeoutType
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.FlowRegion
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferSurface
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.PlatformRegion
import cloud.trotter.dashbuddy.domain.state.Session
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1104/#1114 — offer outcomes on TRANSITION evidence. A Compose control emits no `TYPE_VIEW_CLICKED`
 * for a human tap, so on DoorDash 8.97.8's card neither an ACCEPT nor a DECLINE click can ever arrive.
 * The resolver therefore reads (a) the accepted SURVIVOR the stepper armed on the same step (the
 * click-less accept) and (b) the confirm-decline sheet observed over the offer
 * ([cloud.trotter.dashbuddy.domain.state.PendingOffer.declineSheetSeenAt], set from a screen whose rule
 * declares `state.offerSurface: decline_confirm`). Every accept arm outranks the sheet; no sheet and no
 * click stays a timeout.
 */
class TransitionOutcomeTest {

    private val stepper = PlatformRegionStepper()
    private val policy = TransitionPolicy()
    private val effectMap = EffectMap()

    private fun region() = PlatformRegion(
        platform = Platform.DoorDash,
        mode = Mode.Online,
        session = Session("sess-1", startedAt = 100L),
    )

    private fun order(store: String) = ParsedOrder(
        orderIndex = 0, orderType = OrderType.PICKUP, storeName = store,
        itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
    )

    private fun offerObs(t: Long, hash: String, store: String = "H-E-B", key: String? = null) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.offer_popup",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(
                offerHash = hash, presentationKey = key, payAmount = 14.75, distanceMiles = 8.5,
                timeToCompleteMinutes = 45L, orders = listOf(order(store)),
            ),
        ),
    )

    /** The confirm-decline sheet: `offer:presented`, no parse, `offerSurface: decline_confirm`. */
    private fun sheetObs(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.offer_popup_confirm_decline",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.None, offerSurface = OfferSurface.DECLINE_CONFIRM,
    )

    private fun idleObs(t: Long) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.idle_map",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
        parsed = ParsedFields.None,
    )

    private fun pickupNavObs(t: Long, store: String = "H-E-B") = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.pickup_navigation",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = store),
    )

    private fun expiry(t: Long, hash: String) = Observation.Timeout(
        timestamp = t, type = TimeoutType.OFFER_EXPIRY, payload = ObservationPayload.OfferExpiry(hash),
    )

    private class Driven(val region: PlatformRegion, val prevFlow: FlowRegion)

    private fun drive(start: PlatformRegion, vararg obs: Observation): Driven {
        var region = start
        var prevFlow = FlowRegion()
        for (o in obs) {
            val f = (o as? Observation.FlowObservation)?.flow
            val nextFlow = if (f != null) prevFlow.copy(flow = f, activePlatform = region.platform) else prevFlow
            region = stepper.step(region, prevFlow, nextFlow, o, policy)
            prevFlow = nextFlow
        }
        return Driven(region, prevFlow)
    }

    /** Step [o] once from [d] and return the offer-lifecycle effects that edge produces. */
    private fun resolve(d: Driven, o: Observation): Pair<PlatformRegion, List<AppEffect>> {
        val f = (o as? Observation.FlowObservation)?.flow
        val nextFlow = if (f != null) d.prevFlow.copy(flow = f, activePlatform = d.region.platform) else d.prevFlow
        val next = stepper.step(d.region, d.prevFlow, nextFlow, o, policy)
        return next to effectMap.diffOfferLifecycle(d.region, next, o, sessionId = "sess-1")
    }

    private fun List<AppEffect>.outcome(): OfferPayload? =
        filterIsInstance<AppEffect.LogEvent>().mapNotNull { it.event.payload as? OfferPayload }.singleOrNull()

    @Test
    fun `sheet observed then the offer leaves to Idle resolves as an INFERRED decline`() {
        val d = drive(region(), offerObs(1_000L, "o1"), sheetObs(3_000L))
        assertEquals(3_000L, d.region.presentedOffer()?.declineSheetSeenAt)
        val (next, effects) = resolve(d, idleObs(5_000L))
        val out = effects.outcome()
        assertNotNull("one outcome row", out)
        assertEquals(AppEventType.OFFER_DECLINED, out!!.outcome)
        assertTrue(out.description!!.contains("inferred from the confirm sheet"))
        assertTrue("offer resolved away", next.pendingOffers.isEmpty())
    }

    @Test
    fun `sheet observed then OFFER_EXPIRY fires resolves as a decline, not a timeout`() {
        val d = drive(region(), offerObs(1_000L, "o1"), sheetObs(3_000L))
        val (_, effects) = resolve(d, expiry(130_000L, "o1"))
        assertEquals(AppEventType.OFFER_DECLINED, effects.outcome()!!.outcome)
    }

    @Test
    fun `no sheet and no click stays a TIMEOUT (control)`() {
        val d = drive(region(), offerObs(1_000L, "o1"))
        val (_, effects) = resolve(d, idleObs(5_000L))
        val out = effects.outcome()!!
        assertEquals(AppEventType.OFFER_TIMEOUT, out.outcome)
        assertNull(out.description)
    }

    @Test
    fun `a click-less accept logs OFFER_ACCEPTED (not TIMEOUT) while the job mints`() {
        val d = drive(region(), offerObs(1_000L, "o1"))
        val (next, effects) = resolve(d, pickupNavObs(4_000L))
        val out = effects.outcome()!!
        assertEquals(AppEventType.OFFER_ACCEPTED, out.outcome)
        assertTrue(out.description!!.contains("inferred from the task surface"))
        // The task frame both arms the survivor and consumes it into the minted job on this step.
        assertTrue("economics consumed into the minted job", next.activeJob?.acceptedOffers?.any { it.offerHash == "o1" } == true)
        assertNull("no un-consumed survivor lingers", next.pendingOffers.firstOrNull { it.offerHash == "o1" })
    }

    @Test
    fun `an accept after the sheet outranks the sheet`() {
        val d = drive(region(), offerObs(1_000L, "o1"), sheetObs(3_000L))
        val (_, effects) = resolve(d, pickupNavObs(4_000L))
        assertEquals(AppEventType.OFFER_ACCEPTED, effects.outcome()!!.outcome)
    }

    @Test
    fun `the sheet marker survives an enrich-as-variant re-render and is set once`() {
        val d = drive(
            region(),
            offerObs(1_000L, "o1", key = "k"),
            sheetObs(3_000L),
            offerObs(3_500L, "o1-variant", key = "k"), // same presentation, churned hash
            sheetObs(4_000L),                           // a second sighting must not move the mark
        )
        val presented = d.region.presentedOffer()
        assertEquals("o1-variant", presented?.offerHash)
        assertEquals(3_000L, presented?.declineSheetSeenAt)
    }

    @Test
    fun `a sheet with no presented offer is inert`() {
        val d = drive(region(), sheetObs(3_000L))
        assertTrue(d.region.pendingOffers.isEmpty())
    }
}
