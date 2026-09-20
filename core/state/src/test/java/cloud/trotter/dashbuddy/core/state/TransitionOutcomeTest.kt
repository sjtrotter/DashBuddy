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

    /** Every card frame carries its remaining countdown by default (8/8 fielded Compose cards do). */
    private fun offerObs(t: Long, hash: String, store: String = "H-E-B", key: String? = null, countdown: Int? = 60) = Observation.Screen(
        timestamp = t, captureId = null, ruleId = "doordash.screen.offer_popup",
        metadata = ReplayMetadata.EMPTY, flow = Flow.OfferPresented, modeHint = Mode.Online,
        parsed = ParsedFields.OfferFields(
            parsedOffer = ParsedOffer(
                offerHash = hash, presentationKey = key, payAmount = 14.75, distanceMiles = 8.5,
                timeToCompleteMinutes = 45L, orders = listOf(order(store)), initialCountdownSeconds = countdown,
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
    fun `the OFFER_EXPIRY safety timer never converts a sheet sighting into a decline`() {
        // The timer fires only when NO frame arrived; running out is what it means (Astra r2).
        val d = drive(region(), offerObs(1_000L, "o1"), sheetObs(118_000L))
        val (_, effects) = resolve(d, expiry(121_000L, "o1"))
        val out = effects.outcome()!!
        assertEquals(AppEventType.OFFER_TIMEOUT, out.outcome)
        assertNull(out.description)
    }

    @Test
    fun `a LATE cancel — sheet, cancel, countdown runs out inside the window — is a TIMEOUT (countdown evidence)`() {
        // Astra r2: offer with a 40 s countdown → sheet at 35 s → card at 36 s (countdown 5 s) → idle
        // at 41 s. The exit coincides with the countdown's end, so the sheet does not make it a decline.
        val d = drive(
            region(),
            offerObs(1_000L, "o1", countdown = 40),
            sheetObs(35_000L),
            offerObs(36_000L, "o1", countdown = 5),
        )
        assertEquals(41_000L, d.region.presentedOffer()?.countdownExpiresAt)
        val (_, effects) = resolve(d, idleObs(41_000L))
        val out = effects.outcome()!!
        assertEquals(AppEventType.OFFER_TIMEOUT, out.outcome)
        assertNull(out.description)
    }

    @Test
    fun `a real decline with a live countdown — exit well before the countdown ends — is a DECLINE`() {
        val d = drive(region(), offerObs(1_000L, "o1", countdown = 40), sheetObs(3_000L), offerObs(4_600L, "o1", countdown = 36))
        assertEquals(40_600L, d.region.presentedOffer()?.countdownExpiresAt)
        val (_, effects) = resolve(d, idleObs(6_000L))
        assertEquals(AppEventType.OFFER_DECLINED, effects.outcome()!!.outcome)
    }

    @Test
    fun `a card frame without a countdown keeps the last known countdown end`() {
        val d = drive(region(), offerObs(1_000L, "o1", countdown = 40), offerObs(4_000L, "o1", countdown = null))
        assertEquals(41_000L, d.region.presentedOffer()?.countdownExpiresAt)
    }

    private fun List<AppEffect>.expiryArm(): AppEffect.ScheduleTimeout? =
        filterIsInstance<AppEffect.ScheduleTimeout>().singleOrNull { it.type == cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.OFFER_EXPIRY }

    @Test
    fun `every same-hash countdown move re-arms the expiry timer for the NEW end (drift, extension, shortening)`() {
        // Astra r4: 1 s→40, 4 s→40, 7 s→40 (deadline 41→44→47 s by 3 s steps), 37 s→10 (47 s). A
        // slack-tolerant compare against the previous frame never re-armed and the stale 41 s timer
        // fired with 6 s left. Now each move re-arms; an unchanged deadline does not.
        var d = drive(region(), offerObs(1_000L, "o1", countdown = 40))
        val (r4, e4) = resolve(d, offerObs(4_000L, "o1", countdown = 40))
        assertEquals(44_000L, r4.presentedOffer()?.countdownExpiresAt); assertEquals(40_000L, e4.expiryArm()?.durationMs)
        d = Driven(r4, d.prevFlow)
        val (r7, e7) = resolve(d, offerObs(7_000L, "o1", countdown = 40))
        assertEquals(47_000L, r7.presentedOffer()?.countdownExpiresAt); assertEquals(40_000L, e7.expiryArm()?.durationMs)
        d = Driven(r7, d.prevFlow)
        val (r37, e37) = resolve(d, offerObs(37_000L, "o1", countdown = 10))
        assertEquals("unchanged end", 47_000L, r37.presentedOffer()?.countdownExpiresAt); assertNull("no re-arm when the end did not move", e37.expiryArm())
        d = Driven(r37, d.prevFlow)
        val (r40, e40) = resolve(d, offerObs(40_000L, "o1", countdown = 2)) // SHORTENED to 42 s
        assertEquals(42_000L, r40.presentedOffer()?.countdownExpiresAt); assertEquals(2_000L, e40.expiryArm()?.durationMs)
        assertTrue("no outcome — the offer is live", e40.outcome() == null)
    }

    @Test
    fun `a same-hash countdown move after the evaluation landed re-posts the heads-up with the new expiry, without re-speaking`() {
        // Astra r4 P2: the notification snapshot froze the first deadline.
        val start = drive(region(), offerObs(1_000L, "o1", countdown = 40))
        val landed = start.region.copy(pendingOffers = start.region.pendingOffers.map {
            it.copy(
                evaluation = cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation(
                    action = cloud.trotter.dashbuddy.domain.evaluation.OfferAction.ACCEPT, score = 74.0,
                    qualityLevel = cloud.trotter.dashbuddy.domain.evaluation.OfferQuality.GOOD, payAmount = 14.75,
                    fuelCostEstimate = 0.5, netPayAmount = 12.0, distanceMiles = 8.5, dollarsPerMile = 1.4,
                    dollarsPerHour = 22.0, estimatedTimeMinutes = 33.0, itemCount = 1.0, merchantName = "H-E-B",
                ),
                firstEvalLandedAt = 1_500L,
            )
        })
        val d = Driven(landed, start.prevFlow)
        val (next, effects) = resolve(d, offerObs(21_000L, "o1", countdown = 60)) // → 81 s
        assertEquals(81_000L, next.presentedOffer()?.countdownExpiresAt)
        val post = effects.filterIsInstance<AppEffect.PostOfferNotification>().single()
        assertEquals(81_000L, post.offer.expiresAt)
        assertTrue("a moved deadline is a refresh-only post", post.refreshOnly)
        assertTrue("speak-once holds", effects.none { it is AppEffect.SpeakOffer })
        assertTrue("no chat card for a deadline refresh", effects.none { it is AppEffect.UpdateBubble })
    }

    @Test
    fun `with NO countdown ever read the sheet alone never makes a decline (fail-null)`() {
        // Astra r3: a card whose clock was never parsed cannot separate "declined" from "ran out".
        val d = drive(region(), offerObs(1_000L, "o1", countdown = null), sheetObs(3_000L))
        val (_, effects) = resolve(d, idleObs(5_000L))
        val out = effects.outcome()!!
        assertEquals(AppEventType.OFFER_TIMEOUT, out.outcome)
        assertNull(out.description)
    }

    @Test
    fun `a refreshed countdown on a same-presentation variant keeps the expiry anchored on the frame that read it`() {
        // Astra r3 P1: offer at 1 s reading 0:40; variant at 21 s reading 0:20 → expiry stays 41 s.
        // The OLD formula (presentedAt + countdown) would have armed the timer for 21 s + 1 ms and
        // timed out a live offer.
        val d = drive(region(), offerObs(1_000L, "o1", key = "k", countdown = 40))
        val (next, effects) = resolve(d, offerObs(21_000L, "o1-variant", key = "k", countdown = 20))
        assertEquals(41_000L, next.presentedOffer()?.countdownExpiresAt)
        val arm = effects.filterIsInstance<AppEffect.ScheduleTimeout>().single { it.type == cloud.trotter.dashbuddy.domain.pipeline.TimeoutType.OFFER_EXPIRY }
        assertEquals("re-armed for the true expiry (41 s − 21 s)", 20_000L, arm.durationMs)
        assertTrue("no outcome — the offer is live", effects.outcome() == null)
    }

    @Test
    fun `a cancelled sheet whose offer later expires stays a TIMEOUT (outside the decline window)`() {
        // Astra r1 F3: sheet → cancel back to the card → the card re-renders (as it ALSO does after
        // a real decline, so a card frame is not a cancel signal) → the countdown ends 40 s later.
        val d = drive(region(), offerObs(1_000L, "o1"), sheetObs(3_000L), offerObs(4_500L, "o1"))
        assertEquals("the card frame keeps the sighting", 3_000L, d.region.presentedOffer()?.declineSheetSeenAt)
        val (_, effects) = resolve(d, idleObs(43_000L))
        val out = effects.outcome()!!
        assertEquals(AppEventType.OFFER_TIMEOUT, out.outcome)
        assertNull(out.description)
    }

    @Test
    fun `a re-opened sheet re-arms the window from its latest sighting`() {
        val d = drive(region(), offerObs(1_000L, "o1"), sheetObs(3_000L), offerObs(4_500L, "o1"), sheetObs(30_000L))
        assertEquals(30_000L, d.region.presentedOffer()?.declineSheetSeenAt)
        val (_, effects) = resolve(d, idleObs(36_000L))
        assertEquals(AppEventType.OFFER_DECLINED, effects.outcome()!!.outcome)
    }

    @Test
    fun `a mid-job ADD-ON declined through the sheet is not an accept when the original pickup re-renders`() {
        // Astra r1 F2: job A active (pickup navigation), add-on B presented over it, sheet, then job
        // A's pickup surface re-renders. Before #1104 the phased-destination rule inferred B's accept.
        val d = drive(
            region(),
            offerObs(1_000L, "a", store = "Store A"),
            pickupNavObs(4_000L, "Store A"),          // A minted
            offerObs(10_000L, "b", store = "Store B"), // add-on over a task flow
            sheetObs(12_000L),
        )
        assertEquals(1, d.region.activeJob?.acceptedOffers?.size)
        val (next, effects) = resolve(d, pickupNavObs(14_000L, "Store A"))
        val out = effects.outcome()!!
        assertEquals(AppEventType.OFFER_DECLINED, out.outcome)
        assertTrue(out.description!!.contains("inferred from the confirm sheet"))
        assertEquals("no phantom add-on economics", 1, next.activeJob?.acceptedOffers?.size)
        assertNull("no phantom survivor", next.pendingOffers.firstOrNull { it.offerHash == "b" })
    }

    @Test
    fun `a mid-job ADD-ON that leaves with no sheet stays a TIMEOUT and mints nothing (fail-null)`() {
        val d = drive(
            region(),
            offerObs(1_000L, "a", store = "Store A"),
            pickupNavObs(4_000L, "Store A"),
            offerObs(10_000L, "b", store = "Store B"),
        )
        val (next, effects) = resolve(d, pickupNavObs(14_000L, "Store A"))
        assertEquals(AppEventType.OFFER_TIMEOUT, effects.outcome()!!.outcome)
        assertEquals(1, next.activeJob?.acceptedOffers?.size)
    }

    @Test
    fun `a direct ACCEPT click observation outranks the sheet`() {
        // Astra r1 F4: an observed tap always beats an inference.
        val d = drive(region(), offerObs(1_000L, "o1"), sheetObs(3_000L))
        val click = Observation.Click(
            timestamp = 5_000L, captureId = null, ruleId = "doordash.click.accept_offer",
            metadata = ReplayMetadata.EMPTY, flow = Flow.Idle, modeHint = Mode.Online,
            parsed = ParsedFields.ClickFields(intent = cloud.trotter.dashbuddy.domain.state.OfferIntent.ACCEPT),
        )
        val outcome = effectMap.resolveOfferOutcome(click, d.region.presentedOffer(), d.region, d.region)
        assertEquals(AppEventType.OFFER_ACCEPTED, outcome)
        assertNull("an observed tap is not described as inferred", effectMap.inferredOutcomeNote(d.region.presentedOffer()!!, outcome, click))
    }

    @Test
    fun `a REPLACEMENT after the sheet keeps the inference in the description`() {
        // Astra r1 F5: A → sheet → a different offer B replaces A.
        val d = drive(region(), offerObs(1_000L, "a", store = "Store A"), sheetObs(3_000L))
        val (_, effects) = resolve(d, offerObs(6_000L, "b", store = "Store B"))
        val outs = effects.filterIsInstance<AppEffect.LogEvent>().mapNotNull { it.event.payload as? OfferPayload }
        val replaced = outs.single { it.offerHash == "a" }
        assertEquals(AppEventType.OFFER_DECLINED, replaced.outcome)
        assertTrue(replaced.description!!.startsWith("Replaced by new offer; Decline inferred from the confirm sheet"))
    }

    @Test
    fun `recovery hygiene drops the sheet sighting (evidence, not a decision in flight)`() {
        val d = drive(region(), offerObs(1_000L, "o1"), sheetObs(3_000L))
        val state = cloud.trotter.dashbuddy.domain.state.AppState(
            regions = cloud.trotter.dashbuddy.domain.state.Regions(platforms = mapOf(Platform.DoorDash to d.region)),
            timestamp = 3_000L,
        ).recoveryHygiene(nowMs = 50_000L)
        assertNull(state.regions.platforms[Platform.DoorDash]!!.presentedOffer()!!.declineSheetSeenAt)
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
    fun `the sheet marker survives an enrich-as-variant re-render and tracks the LATEST sighting`() {
        val d = drive(
            region(),
            offerObs(1_000L, "o1", key = "k"),
            sheetObs(3_000L),
            offerObs(3_500L, "o1-variant", key = "k"), // same presentation, churned hash — keeps the mark
        )
        assertEquals("o1-variant", d.region.presentedOffer()?.offerHash)
        assertEquals(3_000L, d.region.presentedOffer()?.declineSheetSeenAt)
        val again = drive(region(), offerObs(1_000L, "o1"), sheetObs(3_000L), sheetObs(4_000L))
        assertEquals("a re-opened sheet re-arms the window", 4_000L, again.region.presentedOffer()?.declineSheetSeenAt)
    }

    @Test
    fun `a sheet with no presented offer is inert`() {
        val d = drive(region(), sheetObs(3_000L))
        assertTrue(d.region.pendingOffers.isEmpty())
    }
}
