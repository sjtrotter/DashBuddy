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
import cloud.trotter.dashbuddy.domain.state.AppState
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.OfferIntent
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import cloud.trotter.dashbuddy.domain.state.TaskSubFlow
import cloud.trotter.dashbuddy.core.state.model.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #438 B3 — platform-owned offers: the multiplatform-correctness properties the move exists for,
 * plus the OFFER_EXPIRY timer contract (vet H1/M5). Drives the FULL [StateMachine] so both the
 * reducer (owned offers) and the effect diffs (OfferEffects) are exercised end-to-end.
 */
class MultiplatformOfferTest {

    private val machine = StateMachine(
        flowStepper = FlowRegionStepper(),
        platformStepper = PlatformRegionStepper(),
        crossPlatformStepper = CrossPlatformRegionStepper(),
        transitionPolicy = TransitionPolicy(),
        effectMap = EffectMap(),
    )

    private fun offerFields(hash: String, store: String, pay: Double) = ParsedFields.OfferFields(
        parsedOffer = ParsedOffer(
            offerHash = hash, payAmount = pay, distanceMiles = 4.0, timeToCompleteMinutes = 18L,
            orders = listOf(
                ParsedOrder(
                    orderIndex = 0, orderType = OrderType.PICKUP, storeName = store,
                    itemCount = 1, isItemCountEstimated = false, badges = emptySet(),
                ),
            ),
        ),
    )

    private fun offerScreen(t: Long, platformRule: String, hash: String, store: String, pay: Double = 12.0) =
        Observation.Screen(
            timestamp = t, captureId = "c$t", ruleId = "$platformRule.screen.offer",
            metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
            parsed = offerFields(hash, store, pay),
        )

    private fun idle(t: Long, platformRule: String) = Observation.Screen(
        timestamp = t, captureId = "c$t", ruleId = "$platformRule.screen.idle",
        metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online, parsed = ParsedFields.None,
    )

    private fun acceptClick(t: Long, platformRule: String) = Observation.Click(
        timestamp = t, captureId = "c$t", ruleId = "$platformRule.click.accept_offer",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = OfferIntent.ACCEPT),
    )

    private fun declineClick(t: Long, platformRule: String) = Observation.Click(
        timestamp = t, captureId = "c$t", ruleId = "$platformRule.click.decline_offer",
        metadata = ReplayMetadata.EMPTY, flow = null, modeHint = null,
        parsed = ParsedFields.ClickFields(intent = OfferIntent.DECLINE),
    )

    private fun pickupNav(t: Long, platformRule: String, store: String) = Observation.Screen(
        timestamp = t, captureId = "c$t", ruleId = "$platformRule.screen.pickup_nav",
        metadata = ReplayMetadata.EMPTY, flow = Flow.TaskPickupNavigation, modeHint = Mode.Online,
        parsed = ParsedFields.TaskFields(phase = TaskPhase.PICKUP, subFlow = TaskSubFlow.NAVIGATION, storeName = store),
    )

    private fun offerExpiry(t: Long, platform: Platform, hash: String) = Observation.Timeout(
        timestamp = t, type = TimeoutType.OFFER_EXPIRY, targetPlatform = platform,
        payload = ObservationPayload.OfferExpiry(hash),
    )

    private fun drive(start: AppState, vararg obs: Observation): Pair<AppState, List<Transition>> {
        var s = start
        val trace = mutableListOf<Transition>()
        for (o in obs) {
            val t = machine.step(s, o)
            trace += t
            s = t.newState
        }
        return s to trace
    }

    private fun List<Transition>.eventTypes(): List<AppEventType> =
        flatMap { it.effects }.filterIsInstance<AppEffect.LogEvent>().map { it.event.type }

    // =====================================================================
    // INTERLEAVE — two concurrent offers on two platforms
    // =====================================================================

    @Test
    fun `a DoorDash offer, an Uber overlay offer, then a DoorDash accept — no cross-platform bleed`() {
        // Bring both platforms online, present a DoorDash offer, then an Uber overlay offer, then
        // accept the DoorDash one. Neither offer may resolve the other; the minted job carries only
        // DoorDash economics; each offer's evaluation is routed to its own platform.
        val (state, trace) = drive(
            AppState(),
            idle(1_000L, "doordash"),
            idle(1_050L, "uber"),
            offerScreen(1_100L, "doordash", "dd-1", "Bill Miller BBQ", pay = 15.0),
            offerScreen(1_200L, "uber", "ub-1", "Uber Diner", pay = 9.0),
            acceptClick(1_300L, "doordash"),
            pickupNav(1_400L, "doordash", "Bill Miller BBQ"),
        )

        val dd = state.regions.platforms.getValue(Platform.DoorDash)
        val uber = state.regions.platforms.getValue(Platform.Uber)

        // DoorDash minted a job from ITS OWN offer.
        assertNotNull("DoorDash minted a job", dd.activeJob)
        assertEquals("job parented to the DoorDash offer", "dd-1", dd.activeJob?.parentOfferHash)
        assertEquals("DoorDash economics recovered", 15.0, dd.activeJob!!.totalPayAmount, 0.001)

        // The Uber offer is untouched — still presented on the Uber region, not resolved/consumed.
        assertEquals("the Uber overlay offer still stands", "ub-1", uber.presentedOffer()?.offerHash)
        assertNull("no cross-platform job on Uber", uber.activeJob)

        // Per-offer eval routing: each EvaluateOffer carried its own platform.
        val evals = trace.flatMap { it.effects }.filterIsInstance<AppEffect.EvaluateOffer>()
        assertEquals(Platform.DoorDash, evals.single { it.offerHash == "dd-1" }.platform)
        assertEquals(Platform.Uber, evals.single { it.offerHash == "ub-1" }.platform)

        // Exactly one OFFER_ACCEPTED (the DoorDash one); the Uber offer produced no resolution event.
        assertEquals("exactly one acceptance", 1, trace.eventTypes().count { it == AppEventType.OFFER_ACCEPTED })
        assertFalse("no spurious decline/timeout of either offer",
            trace.eventTypes().any { it == AppEventType.OFFER_DECLINED || it == AppEventType.OFFER_TIMEOUT })
    }

    // =====================================================================
    // OFFER_EXPIRY timer contract (vet H1/M5)
    // =====================================================================

    @Test
    fun `presenting an offer arms an OFFER_EXPIRY timer carrying the offer hash`() {
        val (_, trace) = drive(AppState(), idle(1_000L, "doordash"), offerScreen(1_100L, "doordash", "dd-1", "HEB"))
        val armed = trace.flatMap { it.effects }.filterIsInstance<AppEffect.ScheduleTimeout>()
            .single { it.type == TimeoutType.OFFER_EXPIRY }
        assertEquals(Platform.DoorDash, armed.platform)
        assertEquals("dd-1", (armed.payload as ObservationPayload.OfferExpiry).offerHash)
    }

    @Test
    fun `an unresolved offer's expiry fire times it out (TIMEOUT event + notification cancel)`() {
        val (afterOffer, _) = drive(AppState(), idle(1_000L, "doordash"), offerScreen(1_100L, "doordash", "dd-1", "HEB"))
        val fire = machine.step(afterOffer, offerExpiry(1_100L + 130_000L, Platform.DoorDash, "dd-1"))

        assertNull("the offer is gone after expiry", fire.newState.regions.platforms.getValue(Platform.DoorDash).presentedOffer())
        val types = listOf(fire).flatMap { it.effects }.filterIsInstance<AppEffect.LogEvent>().map { it.event.type }
        assertTrue("expiry logs OFFER_TIMEOUT", types.contains(AppEventType.OFFER_TIMEOUT))
        assertTrue("expiry cancels the offer notification",
            fire.effects.filterIsInstance<AppEffect.CancelOfferNotification>().any { it.offerHash == "dd-1" })
    }

    @Test
    fun `an accept-latched offer's expiry fire NO-OPS (never a false timeout, never destroys the mint)`() {
        val (latched, _) = drive(
            AppState(),
            idle(1_000L, "doordash"),
            offerScreen(1_100L, "doordash", "dd-1", "HEB"),
            acceptClick(1_150L, "doordash"),
        )
        val fire = machine.step(latched, offerExpiry(1_100L + 130_000L, Platform.DoorDash, "dd-1"))

        assertNotNull("the accept-latched offer survives its own expiry fire",
            fire.newState.regions.platforms.getValue(Platform.DoorDash).presentedOffer())
        val types = listOf(fire).flatMap { it.effects }.filterIsInstance<AppEffect.LogEvent>().map { it.event.type }
        assertFalse("no false OFFER_TIMEOUT on an accepted offer", types.contains(AppEventType.OFFER_TIMEOUT))
    }

    @Test
    fun `an expiry fire whose hash does not match the current offer is a no-op`() {
        val (afterOffer, _) = drive(AppState(), idle(1_000L, "doordash"), offerScreen(1_100L, "doordash", "dd-1", "HEB"))
        val fire = machine.step(afterOffer, offerExpiry(1_100L + 130_000L, Platform.DoorDash, "STALE-HASH"))
        assertEquals("the current offer is untouched by a stale-hash expiry",
            "dd-1", fire.newState.regions.platforms.getValue(Platform.DoorDash).presentedOffer()?.offerHash)
    }

    // =====================================================================
    // EVENT CONTRACT — single-platform streams (type + payload + edge)
    // =====================================================================

    @Test
    fun `single-platform accept stream — RECEIVED then ACCEPTED with the accept payload`() {
        val (_, trace) = drive(
            AppState(),
            idle(1_000L, "doordash"),
            offerScreen(1_100L, "doordash", "dd-1", "HEB", pay = 11.0),
            acceptClick(1_150L, "doordash"),
            pickupNav(1_200L, "doordash", "HEB"),
        )
        val offerEvents = trace.flatMap { it.effects }.filterIsInstance<AppEffect.LogEvent>()
            .filter { it.event.type in setOf(AppEventType.OFFER_RECEIVED, AppEventType.OFFER_ACCEPTED, AppEventType.OFFER_DECLINED, AppEventType.OFFER_TIMEOUT) }
        assertEquals(
            listOf(AppEventType.OFFER_RECEIVED, AppEventType.OFFER_ACCEPTED),
            offerEvents.map { it.event.type },
        )
        val accepted = offerEvents.last().event.payload as OfferPayload
        assertEquals("dd-1", accepted.offerHash)
        assertEquals(AppEventType.OFFER_ACCEPTED, accepted.outcome)
    }

    @Test
    fun `single-platform decline stream — RECEIVED then DECLINED (#594 latch stands)`() {
        val (_, trace) = drive(
            AppState(),
            idle(1_000L, "doordash"),
            offerScreen(1_100L, "doordash", "dd-1", "HEB"),
            declineClick(1_150L, "doordash"),
            idle(1_200L, "doordash"),
        )
        val offerEvents = trace.eventTypes()
            .filter { it in setOf(AppEventType.OFFER_RECEIVED, AppEventType.OFFER_ACCEPTED, AppEventType.OFFER_DECLINED, AppEventType.OFFER_TIMEOUT) }
        assertEquals(listOf(AppEventType.OFFER_RECEIVED, AppEventType.OFFER_DECLINED), offerEvents)
    }

    @Test
    fun `single-platform timeout stream — RECEIVED then TIMEOUT on an un-acted offer leaving to idle`() {
        val (_, trace) = drive(
            AppState(),
            idle(1_000L, "doordash"),
            offerScreen(1_100L, "doordash", "dd-1", "HEB"),
            idle(1_200L, "doordash"), // offer vanished with no accept/decline
        )
        val offerEvents = trace.eventTypes()
            .filter { it in setOf(AppEventType.OFFER_RECEIVED, AppEventType.OFFER_ACCEPTED, AppEventType.OFFER_DECLINED, AppEventType.OFFER_TIMEOUT) }
        assertEquals(listOf(AppEventType.OFFER_RECEIVED, AppEventType.OFFER_TIMEOUT), offerEvents)
    }
}
