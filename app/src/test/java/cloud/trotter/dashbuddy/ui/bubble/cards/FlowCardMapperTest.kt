package cloud.trotter.dashbuddy.ui.bubble.cards

import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.evaluation.OfferAction
import cloud.trotter.dashbuddy.domain.evaluation.OfferEvaluation
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.AppEventPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStartPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.SessionStopPayload
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPay
import cloud.trotter.dashbuddy.domain.model.pay.ParsedPayItem
import cloud.trotter.dashbuddy.domain.state.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cloud.trotter.dashbuddy.domain.evaluation.OfferQuality
import cloud.trotter.dashbuddy.domain.state.UNKNOWN_STORE

class FlowCardMapperTest {

    private fun event(type: AppEventType, payload: AppEventPayload?, occurredAt: Long) = AppEvent(
        type = type,
        occurredAt = occurredAt,
        sessionId = "session-1",
        payload = payload,
    )

    private fun parsedOffer(hash: String, pay: Double, miles: Double, store: String = "Wendy's") =
        ParsedOffer(
            offerHash = hash,
            payAmount = pay,
            distanceMiles = miles,
            itemCount = 1,
            orders = listOf(
                ParsedOrder(
                    orderIndex = 0,
                    orderType = OrderType.PICKUP,
                    storeName = store,
                    itemCount = 1,
                    isItemCountEstimated = false,
                    badges = emptySet(),
                )
            ),
        )

    private fun evaluation(score: Double = 80.0) = OfferEvaluation(
        action = OfferAction.ACCEPT,
        score = score,
        qualityLevel = OfferQuality.GOOD,
        payAmount = 7.50,
        fuelCostEstimate = 0.5,
        nonFuelCostEstimate = 0.5,
        totalOperatingCost = 1.0,
        operatingCostPerMile = 0.24,
        netPayAmount = 6.50,
        distanceMiles = 4.2,
        dollarsPerMile = 1.55,
        dollarsPerHour = 22.0,
        estimatedTimeMinutes = 18.0,
        itemCount = 1.0,
        merchantName = "Wendy's",
    )

    private fun offerPayload(hash: String, outcome: AppEventType, presentedAt: Long, decidedAt: Long) =
        OfferPayload(
            offerHash = hash,
            parsedOffer = parsedOffer(hash, 7.50, 4.2),
            evaluation = evaluation(),
            outcome = outcome,
            presentedAt = presentedAt,
            decidedAt = decidedAt,
            returnFlow = Flow.Idle,
        )

    private fun pickupPayload(taskId: String, jobId: String, store: String, started: Long,
                              arrived: Long? = null, confirmed: Long? = null) = PickupPayload(
        jobId = jobId,
        taskId = taskId,
        storeName = store,
        phaseStartedAt = started,
        arrivedAt = arrived,
        confirmedAt = confirmed,
    )

    private fun deliveryPayload(taskId: String, jobId: String, started: Long,
                                arrived: Long? = null, completed: Long? = null,
                                totalPay: Double? = null, parsedPay: ParsedPay? = null,
                                sessionEarnings: Double? = null) = DeliveryPayload(
        jobId = jobId,
        taskId = taskId,
        customerHash = "cust-abc",
        phaseStartedAt = started,
        arrivedAt = arrived,
        completedAt = completed,
        totalPay = totalPay,
        parsedPay = parsedPay,
        sessionEarningsAtCompletion = sessionEarnings,
    )

    // =========================================================================
    // Happy path
    // =========================================================================

    @Test
    fun `happy path — offer → pickup → delivery → posttask produces five completed cards`() {
        val parsedPay = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 4.50)),
            customerTips = listOf(ParsedPayItem("Wendy's", 3.00)),
        )
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            event(AppEventType.OFFER_RECEIVED, null, occurredAt = 2000L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("offer-1", AppEventType.OFFER_ACCEPTED, 2000L, 2500L),
                occurredAt = 2500L),
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload("T1", "J1", "Wendy's", 2500L),
                occurredAt = 2500L),
            event(AppEventType.PICKUP_ARRIVED,
                pickupPayload("T1", "J1", "Wendy's", 2500L, arrived = 3000L),
                occurredAt = 3000L),
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T1", "J1", "Wendy's", 2500L, arrived = 3000L, confirmed = 3500L),
                occurredAt = 3500L),
            event(AppEventType.DELIVERY_NAV_STARTED,
                deliveryPayload("T2", "J1", 3500L),
                occurredAt = 3500L),
            event(AppEventType.DELIVERY_ARRIVED,
                deliveryPayload("T2", "J1", 3500L, arrived = 4000L),
                occurredAt = 4000L),
            event(AppEventType.DELIVERY_COMPLETED,
                deliveryPayload("T2", "J1", 3500L, arrived = 4000L, completed = 4500L,
                    totalPay = 7.50, parsedPay = parsedPay, sessionEarnings = 47.50),
                occurredAt = 4500L),
        )

        val cards = FlowCardMapper.fold(events)
        assertEquals(5, cards.size)

        val awaiting = cards[0] as FlowCardSnapshot.Awaiting
        assertEquals("s1", awaiting.sessionId)
        assertEquals(1000L, awaiting.phaseStartedAt)
        assertEquals(2000L, awaiting.phaseEndedAt)

        val offer = cards[1] as FlowCardSnapshot.Offer
        assertEquals("offer-1", offer.offerHash)
        assertEquals(7.50, offer.payAmount!!, 0.001)
        assertEquals(80.0, offer.evaluationScore!!, 0.001)
        assertEquals(AppEventType.OFFER_ACCEPTED, offer.outcome)
        assertTrue("Wendy's" in offer.storeNames)

        val pickup = cards[2] as FlowCardSnapshot.Pickup
        assertEquals("T1", pickup.taskId)
        assertEquals("Wendy's", pickup.storeName)
        assertEquals(3000L, pickup.arrivedAt)
        assertEquals(3500L, pickup.confirmedAt)
        assertEquals(3500L, pickup.phaseEndedAt)

        val delivery = cards[3] as FlowCardSnapshot.Delivery
        assertEquals("T2", delivery.taskId)
        assertEquals(4000L, delivery.arrivedAt)
        assertEquals(4000L, delivery.phaseEndedAt)

        val postTask = cards[4] as FlowCardSnapshot.PostTask
        assertEquals(7.50, postTask.totalPay, 0.001)
        assertNotNull(postTask.parsedPay)
        assertEquals(47.50, postTask.sessionEarningsAtCompletion!!, 0.001)
        assertEquals(4000L, postTask.phaseStartedAt)
        assertEquals(4500L, postTask.phaseEndedAt)
    }

    @Test
    fun `no-arrival delivery (DELIVERY_CONFIRMED only) closes Delivery card and opens PostTask`() {
        // DoorDash drop-off doesn't surface a recognized ARRIVED screen. The
        // stepper emits DELIVERY_CONFIRMED when the active dropoff task transitions
        // away (PostTask entry, next offer, etc.); that signal closes the Delivery
        // card. DELIVERY_COMPLETED still fires later with pay breakdown.
        // Sequence mirrors the happy-path but DELIVERY_ARRIVED is absent.
        val parsedPay = ParsedPay(
            appPayComponents = listOf(ParsedPayItem("Base Pay", 4.50)),
            customerTips = listOf(ParsedPayItem("Wendy's", 3.00)),
        )
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            event(AppEventType.OFFER_RECEIVED, null, occurredAt = 2000L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("offer-1", AppEventType.OFFER_ACCEPTED, 2000L, 2500L),
                occurredAt = 2500L),
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload("T1", "J1", "Wendy's", 2500L),
                occurredAt = 2500L),
            event(AppEventType.PICKUP_ARRIVED,
                pickupPayload("T1", "J1", "Wendy's", 2500L, arrived = 3000L),
                occurredAt = 3000L),
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T1", "J1", "Wendy's", 2500L, arrived = 3000L, confirmed = 3500L),
                occurredAt = 3500L),
            event(AppEventType.DELIVERY_NAV_STARTED,
                deliveryPayload("T2", "J1", 3500L),
                occurredAt = 3500L),
            // No DELIVERY_ARRIVED — DoorDash drop-off skips this.
            event(AppEventType.DELIVERY_CONFIRMED,
                deliveryPayload("T2", "J1", 3500L),
                occurredAt = 4200L),
            event(AppEventType.DELIVERY_COMPLETED,
                deliveryPayload("T2", "J1", 3500L, completed = 4500L,
                    totalPay = 7.50, parsedPay = parsedPay, sessionEarnings = 47.50),
                occurredAt = 4500L),
        )

        val cards = FlowCardMapper.fold(events)
        // Expected: Awaiting, Offer, Pickup, Delivery, PostTask
        assertEquals(5, cards.size)

        val delivery = cards[3] as FlowCardSnapshot.Delivery
        assertEquals("T2", delivery.taskId)
        assertEquals(4200L, delivery.phaseEndedAt)
        assertNull("arrivedAt stays null for no-arrival deliveries", delivery.arrivedAt)

        val postTask = cards[4] as FlowCardSnapshot.PostTask
        assertEquals(7.50, postTask.totalPay, 0.001)
        assertEquals(4500L, postTask.phaseEndedAt)
    }

    // =========================================================================
    // Duplicate-key crash regressions (field DB 2026-06-03)
    // =========================================================================

    @Test
    fun `delivery with BOTH arrival and confirmed produces ONE delivery card (no duplicate key)`() {
        // Field crash: arrival-bearing dropoffs (photo / PIN / hand-it /
        // alcohol ID-scan) fire DELIVERY_ARRIVED *and* DELIVERY_CONFIRMED for
        // the same taskId. The mapper added a `delivery:<taskId>` card for each
        // → duplicate LazyColumn key → fatal crash (taskId c0041f37,
        // ARRIVED 17:59:23 → CONFIRMED 17:59:33 → crash 17:59:34).
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"), 1000L),
            event(AppEventType.OFFER_RECEIVED, null, 1500L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("o1", AppEventType.OFFER_ACCEPTED, 1500L, 1600L), 1600L),
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload("T1", "J1", "Wendy's", 1600L), 1600L),
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T1", "J1", "Wendy's", 1600L, confirmed = 2000L), 2000L),
            event(AppEventType.DELIVERY_NAV_STARTED,
                deliveryPayload("T2", "J1", 2000L), 2000L),
            event(AppEventType.DELIVERY_ARRIVED,
                deliveryPayload("T2", "J1", 2000L, arrived = 2500L), 2500L),
            event(AppEventType.DELIVERY_CONFIRMED,
                deliveryPayload("T2", "J1", 2000L, arrived = 2500L), 2600L),
            event(AppEventType.DELIVERY_COMPLETED,
                deliveryPayload("T2", "J1", 2000L, arrived = 2500L, completed = 2700L, totalPay = 7.50), 2700L),
        )

        val cards = FlowCardMapper.fold(events)

        // Exactly one delivery card, and all card ids are unique (the invariant
        // the LazyColumn key requires).
        val deliveries = cards.filterIsInstance<FlowCardSnapshot.Delivery>()
        assertEquals(1, deliveries.size)
        assertEquals("T2", deliveries[0].taskId)
        assertEquals(cards.map { it.id }.distinct().size, cards.size)
    }

    @Test
    fun `double DELIVERY_CONFIRMED for same task produces ONE delivery card`() {
        // Same field session, 22:00 crash: taskId 4d62f8ea fired ARRIVED then
        // DELIVERY_CONFIRMED twice (22:00:37 and 22:00:41).
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"), 1000L),
            event(AppEventType.DELIVERY_NAV_STARTED,
                deliveryPayload("D1", "J1", 2000L), 2000L),
            event(AppEventType.DELIVERY_ARRIVED,
                deliveryPayload("D1", "J1", 2000L, arrived = 2500L), 2500L),
            event(AppEventType.DELIVERY_CONFIRMED,
                deliveryPayload("D1", "J1", 2000L, arrived = 2500L), 2600L),
            event(AppEventType.DELIVERY_CONFIRMED,
                deliveryPayload("D1", "J1", 2000L, arrived = 2500L), 2640L),
        )

        val cards = FlowCardMapper.fold(events)
        assertEquals(1, cards.filterIsInstance<FlowCardSnapshot.Delivery>().size)
        assertEquals(cards.map { it.id }.distinct().size, cards.size)
    }

    @Test
    fun `same offer hash decided twice produces ONE offer card`() {
        // The offer:<hash> crash family (field 2026-05-25) — the same offer
        // re-presented and re-decided would add two offer cards with the same
        // offerHash → duplicate key. Dedup keeps the last decision.
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"), 1000L),
            event(AppEventType.OFFER_RECEIVED, null, 1500L),
            event(AppEventType.OFFER_DECLINED,
                offerPayload("dup-hash", AppEventType.OFFER_DECLINED, 1500L, 1700L), 1700L),
            event(AppEventType.OFFER_RECEIVED, null, 2000L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("dup-hash", AppEventType.OFFER_ACCEPTED, 2000L, 2100L), 2100L),
        )

        val cards = FlowCardMapper.fold(events)
        val offers = cards.filterIsInstance<FlowCardSnapshot.Offer>()
        assertEquals(1, offers.size)
        // Last decision wins.
        assertEquals(AppEventType.OFFER_ACCEPTED, offers[0].outcome)
        assertEquals(cards.map { it.id }.distinct().size, cards.size)
    }

    // =========================================================================
    // Decline path
    // =========================================================================

    @Test
    fun `decline path — Awaiting closes, Offer freezes with DECLINED outcome, no Pickup`() {
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            event(AppEventType.OFFER_RECEIVED, null, occurredAt = 2000L),
            event(AppEventType.OFFER_DECLINED,
                offerPayload("decline-1", AppEventType.OFFER_DECLINED, 2000L, 2300L),
                occurredAt = 2300L),
        )

        val cards = FlowCardMapper.fold(events)
        assertEquals(2, cards.size)
        assertTrue(cards[0] is FlowCardSnapshot.Awaiting)

        val offer = cards[1] as FlowCardSnapshot.Offer
        assertEquals(AppEventType.OFFER_DECLINED, offer.outcome)
    }

    // =========================================================================
    // Timeout path
    // =========================================================================

    @Test
    fun `timeout path — Offer freezes with TIMEOUT outcome`() {
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            event(AppEventType.OFFER_RECEIVED, null, occurredAt = 2000L),
            event(AppEventType.OFFER_TIMEOUT,
                offerPayload("to-1", AppEventType.OFFER_TIMEOUT, 2000L, 2030L),
                occurredAt = 2030L),
        )

        val cards = FlowCardMapper.fold(events)
        val offer = cards.filterIsInstance<FlowCardSnapshot.Offer>().single()
        assertEquals(AppEventType.OFFER_TIMEOUT, offer.outcome)
    }

    // =========================================================================
    // Multi-delivery dash
    // =========================================================================

    @Test
    fun `back-to-back deliveries produce sequential Offer Pickup Delivery PostTask cards`() {
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            // delivery 1
            event(AppEventType.OFFER_RECEIVED, null, occurredAt = 1500L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("o1", AppEventType.OFFER_ACCEPTED, 1500L, 1600L), 1600L),
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload("T1a", "J1", "A", 1600L), 1600L),
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T1a", "J1", "A", 1600L, confirmed = 2000L), 2000L),
            event(AppEventType.DELIVERY_NAV_STARTED,
                deliveryPayload("T1b", "J1", 2000L), 2000L),
            event(AppEventType.DELIVERY_ARRIVED,
                deliveryPayload("T1b", "J1", 2000L, arrived = 2500L), 2500L),
            event(AppEventType.DELIVERY_COMPLETED,
                deliveryPayload("T1b", "J1", 2000L, arrived = 2500L, completed = 2700L, totalPay = 5.00), 2700L),
            // delivery 2
            event(AppEventType.OFFER_RECEIVED, null, occurredAt = 3000L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("o2", AppEventType.OFFER_ACCEPTED, 3000L, 3100L), 3100L),
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload("T2a", "J2", "B", 3100L), 3100L),
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T2a", "J2", "B", 3100L, confirmed = 3500L), 3500L),
            event(AppEventType.DELIVERY_NAV_STARTED,
                deliveryPayload("T2b", "J2", 3500L), 3500L),
            event(AppEventType.DELIVERY_ARRIVED,
                deliveryPayload("T2b", "J2", 3500L, arrived = 4000L), 4000L),
            event(AppEventType.DELIVERY_COMPLETED,
                deliveryPayload("T2b", "J2", 3500L, arrived = 4000L, completed = 4200L, totalPay = 8.00), 4200L),
        )

        val cards = FlowCardMapper.fold(events)

        // Awaiting(start) + Offer1 + Pickup1 + Delivery1 + PostTask1 +
        // Awaiting(between) + Offer2 + Pickup2 + Delivery2 + PostTask2 = 10
        assertEquals(10, cards.size)

        val offers = cards.filterIsInstance<FlowCardSnapshot.Offer>()
        assertEquals(2, offers.size)
        assertEquals(listOf("o1", "o2"), offers.map { it.offerHash })

        val pickups = cards.filterIsInstance<FlowCardSnapshot.Pickup>()
        assertEquals(2, pickups.size)
        assertEquals(listOf("T1a", "T2a"), pickups.map { it.taskId })

        val postTasks = cards.filterIsInstance<FlowCardSnapshot.PostTask>()
        assertEquals(listOf(5.00, 8.00), postTasks.map { it.totalPay })

        // Two Awaiting cards: one at session start, one between deliveries.
        val awaitings = cards.filterIsInstance<FlowCardSnapshot.Awaiting>()
        assertEquals(2, awaitings.size)
        // Second Awaiting opens at delivery-1 completion (2700L) and closes
        // when delivery-2's OFFER_RECEIVED fires (3000L).
        assertEquals(2700L, awaitings[1].phaseStartedAt)
        assertEquals(3000L, awaitings[1].phaseEndedAt)
    }

    // =========================================================================
    // Awaiting re-opens between deliveries / after declined/timed-out offers
    // =========================================================================

    @Test
    fun `declined then accepted offer produces two Awaiting cards in stack`() {
        // Field log 2026-05-19 #7: post-session card stack should include an
        // Awaiting block for each between-offer / between-delivery gap.
        // Sequence: start → decline → accept → full delivery → DASH_STOP.
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            event(AppEventType.OFFER_RECEIVED, null, 1500L),
            event(AppEventType.OFFER_DECLINED,
                offerPayload("decline-1", AppEventType.OFFER_DECLINED, 1500L, 1700L), 1700L),
            // Dasher returns to awaiting — Awaiting #2 opens at 1700L.
            event(AppEventType.OFFER_RECEIVED, null, 2000L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("o1", AppEventType.OFFER_ACCEPTED, 2000L, 2100L), 2100L),
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload("T1", "J1", "A", 2100L), 2100L),
            event(AppEventType.DELIVERY_NAV_STARTED,
                deliveryPayload("T2", "J1", 2200L), 2200L),
            event(AppEventType.DELIVERY_COMPLETED,
                deliveryPayload("T2", "J1", 2200L, completed = 3000L, totalPay = 7.00), 3000L),
            event(AppEventType.DASH_STOP, null, 3500L),
        )
        val cards = FlowCardMapper.fold(events)
        val awaitings = cards.filterIsInstance<FlowCardSnapshot.Awaiting>()
        // Three Awaiting cards: start, after decline, after delivery.
        assertEquals(3, awaitings.size)
        assertEquals(1000L, awaitings[0].phaseStartedAt)  // session start
        assertEquals(1700L, awaitings[1].phaseStartedAt)  // after decline
        assertEquals(3000L, awaitings[2].phaseStartedAt)  // after delivery
    }

    @Test
    fun `timeout opens a new Awaiting card`() {
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            event(AppEventType.OFFER_RECEIVED, null, 1500L),
            event(AppEventType.OFFER_TIMEOUT,
                offerPayload("to-1", AppEventType.OFFER_TIMEOUT, 1500L, 1530L), 1530L),
            event(AppEventType.DASH_STOP, null, 2000L),
        )
        val cards = FlowCardMapper.fold(events)
        val awaitings = cards.filterIsInstance<FlowCardSnapshot.Awaiting>()
        assertEquals(2, awaitings.size)
        assertEquals(1530L, awaitings[1].phaseStartedAt)  // opened at timeout decidedAt
    }

    @Test
    fun `accepted offer does NOT open Awaiting between Offer and Pickup`() {
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            event(AppEventType.OFFER_RECEIVED, null, 1500L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("o1", AppEventType.OFFER_ACCEPTED, 1500L, 1600L), 1600L),
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload("T1", "J1", "A", 1600L), 1600L),
            event(AppEventType.DASH_STOP, null, 2000L),
        )
        val cards = FlowCardMapper.fold(events)
        val awaitings = cards.filterIsInstance<FlowCardSnapshot.Awaiting>()
        // Only the session-start Awaiting — no spurious one between Offer and Pickup.
        assertEquals(1, awaitings.size)
    }

    // =========================================================================
    // Session reset
    // =========================================================================

    @Test
    fun `DASH_START resets the stack — prior session cards are dropped`() {
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"), 1000L),
            event(AppEventType.OFFER_RECEIVED, null, 1500L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("o1", AppEventType.OFFER_ACCEPTED, 1500L, 1600L), 1600L),
            // new dash starts before delivery completes
            event(AppEventType.DASH_START,
                SessionStartPayload("s2", "DoorDash", 5000L, "interaction", "WaitingForOffer"), 5000L),
            event(AppEventType.OFFER_RECEIVED, null, 5500L),
            event(AppEventType.OFFER_DECLINED,
                offerPayload("o2", AppEventType.OFFER_DECLINED, 5500L, 5700L), 5700L),
        )

        val cards = FlowCardMapper.fold(events)
        // Only the second session's cards remain — Awaiting + Offer
        assertEquals(2, cards.size)
        assertEquals("s2", (cards[0] as FlowCardSnapshot.Awaiting).sessionId)
        assertEquals("o2", (cards[1] as FlowCardSnapshot.Offer).offerHash)
    }

    // =========================================================================
    // Store-name update (secondary PICKUP_NAV_STARTED for the same taskId)
    // =========================================================================

    @Test
    fun `repeated PICKUP_NAV_STARTED for same task updates store name, does not duplicate`() {
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"), 1000L),
            event(AppEventType.OFFER_RECEIVED, null, 1500L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("o1", AppEventType.OFFER_ACCEPTED, 1500L, 1600L), 1600L),
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload("T1", "J1", "Unknown", 1600L), 1600L),
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload("T1", "J1", "Wendy's", 1600L), 1700L),
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T1", "J1", "Wendy's", 1600L, confirmed = 2000L), 2000L),
        )

        val cards = FlowCardMapper.fold(events)
        val pickups = cards.filterIsInstance<FlowCardSnapshot.Pickup>()
        assertEquals(1, pickups.size)
        assertEquals("Wendy's", pickups[0].storeName)
    }

    // =========================================================================
    // Half-open card on DASH_STOP
    // =========================================================================

    @Test
    fun `DASH_STOP closes any half-open card with the stop timestamp`() {
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"), 1000L),
            // Awaiting open, never gets an offer
            event(AppEventType.DASH_STOP,
                SessionStopPayload("s1", endedAt = 3000L, source = "early_offline"), 3000L),
        )

        val cards = FlowCardMapper.fold(events)
        assertEquals(1, cards.size)
        val awaiting = cards[0] as FlowCardSnapshot.Awaiting
        assertEquals(3000L, awaiting.phaseEndedAt)
    }

    // =========================================================================
    // Empty input
    // =========================================================================

    @Test
    fun `empty event list produces empty card list`() {
        val cards = FlowCardMapper.fold(emptyList())
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `Awaiting closes at presentedAt when OFFER_RECEIVED carries typed payload`() {
        // OFFER_RECEIVED is now emitted by EffectMap with a typed
        // OfferReceivedPayload that includes presentedAt. The mapper uses
        // that timestamp (the moment the offer hit the screen) to close
        // Awaiting — more accurate than the event's own occurredAt.
        val received = OfferReceivedPayload(
            offerHash = "o1",
            parsedOffer = parsedOffer("o1", 7.50, 4.2),
            presentedAt = 1500L,
            platform = "DoorDash",
            returnFlow = Flow.Idle,
        )
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            // Note: event occurredAt (1505L) deliberately differs from
            // payload presentedAt (1500L) to prove the mapper prefers the
            // payload's value.
            event(AppEventType.OFFER_RECEIVED, received, occurredAt = 1505L),
        )

        val cards = FlowCardMapper.fold(events)
        assertEquals(1, cards.size)
        val awaiting = cards[0] as FlowCardSnapshot.Awaiting
        assertEquals(1000L, awaiting.phaseStartedAt)
        assertEquals(1500L, awaiting.phaseEndedAt)
    }

    @Test
    fun `Awaiting closes on OFFER_ACCEPTED even when OFFER_RECEIVED is missing`() {
        // Regression: the rule-declared OFFER_RECEIVED log effect doesn't
        // persist to the DB (it only goes to Timber). The mapper must
        // defensively close the Awaiting card on the next OFFER_* closing
        // event using OfferPayload.presentedAt as the phaseEndedAt — see
        // bubble-test session 2026-05-17 where no OFFER_RECEIVED row was
        // ever written and the Awaiting card hung open until DASH_STOP.
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"),
                occurredAt = 1000L),
            // Note: no OFFER_RECEIVED here — straight to ACCEPTED.
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("abc", AppEventType.OFFER_ACCEPTED, presentedAt = 1500L, decidedAt = 1600L),
                occurredAt = 1600L),
        )

        val cards = FlowCardMapper.fold(events)
        assertEquals(2, cards.size)

        val awaiting = cards[0] as FlowCardSnapshot.Awaiting
        assertEquals(1000L, awaiting.phaseStartedAt)
        // Critical: Awaiting closes at the offer's presentedAt (1500L), NOT
        // at the closing-event occurredAt (1600L) and NOT left open.
        assertEquals(1500L, awaiting.phaseEndedAt)

        val offer = cards[1] as FlowCardSnapshot.Offer
        assertEquals("abc", offer.offerHash)
        assertEquals(AppEventType.OFFER_ACCEPTED, offer.outcome)
    }

    @Test
    fun `events before DASH_START are ignored when DASH_START arrives`() {
        // A real session: prior stale offer events followed by a new DASH_START
        // should not contaminate the new session's stack.
        val events = listOf(
            event(AppEventType.OFFER_RECEIVED, null, 500L),
            event(AppEventType.OFFER_DECLINED,
                offerPayload("stale", AppEventType.OFFER_DECLINED, 500L, 700L), 700L),
            event(AppEventType.DASH_START,
                SessionStartPayload("s-new", "DoorDash", 1000L, "interaction", "WaitingForOffer"), 1000L),
        )
        val cards = FlowCardMapper.fold(events)
        // Stale Offer card is dropped by the DASH_START reset; the new
        // Awaiting card is still open (no end-time yet).
        assertEquals(0, cards.size)
    }

    // ── #403: blank store names render the same fallback as the live card ──

    @Test
    fun `a blank store name on PICKUP_NAV_STARTED falls back to Unknown like the live card`() {
        val events = listOf(
            event(AppEventType.PICKUP_NAV_STARTED,
                pickupPayload(taskId = "t1", jobId = "j1", store = "", started = 1000L), 1000L),
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload(taskId = "t1", jobId = "j1", store = "", started = 1000L, confirmed = 2000L), 2000L),
        )
        val cards = FlowCardMapper.fold(events)
        val pickup = cards.filterIsInstance<FlowCardSnapshot.Pickup>().single()
        assertEquals(UNKNOWN_STORE, pickup.storeName)
    }

    // ── #461: Shop & Deliver gets a SHOP badge ──

    @Test
    fun `a Shop & Deliver offer gets a synthetic SHOP badge`() {
        val shopOffer = ParsedOffer(
            offerHash = "shop-1", payAmount = 22.0, distanceMiles = 3.0, itemCount = 18,
            orders = listOf(
                ParsedOrder(
                    orderIndex = 0, orderType = OrderType.SHOP_FOR_ITEMS, storeName = "H-E-B",
                    itemCount = 18, isItemCountEstimated = false, badges = emptySet(),
                ),
            ),
        )
        val events = listOf(
            event(
                AppEventType.OFFER_ACCEPTED,
                OfferPayload(
                    offerHash = "shop-1", parsedOffer = shopOffer, evaluation = evaluation(),
                    outcome = AppEventType.OFFER_ACCEPTED, presentedAt = 2000L, decidedAt = 2500L,
                    returnFlow = Flow.Idle,
                ),
                occurredAt = 2500L,
            ),
        )
        val offer = FlowCardMapper.fold(events).filterIsInstance<FlowCardSnapshot.Offer>().single()
        assertTrue("Shop & Deliver offer must carry the SHOP badge", offer.badges.contains("SHOP"))
    }

    @Test
    fun `a plain pickup offer does NOT get a SHOP badge`() {
        val events = listOf(
            event(
                AppEventType.OFFER_ACCEPTED,
                offerPayload("plain-1", AppEventType.OFFER_ACCEPTED, 2000L, 2500L),
                occurredAt = 2500L,
            ),
        )
        val offer = FlowCardMapper.fold(events).filterIsInstance<FlowCardSnapshot.Offer>().single()
        assertTrue("plain pickup must not carry SHOP", !offer.badges.contains("SHOP"))
    }

    @Test
    fun `accepted offer economics thread onto the pickup and delivery cards (#460)`() {
        // The "Running at $/hr" co-hero needs netPay + estMinutes on the task
        // cards. They come from the accepted offer's evaluation (netPayAmount
        // 6.50, estimatedTimeMinutes 18.0 in the test fixture).
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"), 1000L),
            event(AppEventType.OFFER_ACCEPTED,
                offerPayload("o1", AppEventType.OFFER_ACCEPTED, 2000L, 2500L), 2500L),
            event(AppEventType.PICKUP_NAV_STARTED, pickupPayload("T1", "J1", "Wendy's", 2500L), 2500L),
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T1", "J1", "Wendy's", 2500L, arrived = 3000L, confirmed = 3500L), 3500L),
            event(AppEventType.DELIVERY_NAV_STARTED, deliveryPayload("T2", "J1", 3500L), 3500L),
            event(AppEventType.DELIVERY_ARRIVED, deliveryPayload("T2", "J1", 3500L, arrived = 4000L), 4000L),
        )
        val cards = FlowCardMapper.fold(events)
        val pickup = cards.filterIsInstance<FlowCardSnapshot.Pickup>().single()
        val delivery = cards.filterIsInstance<FlowCardSnapshot.Delivery>().single()

        assertEquals(6.50, pickup.netPay!!, 0.001)
        assertEquals(18.0, pickup.estMinutes!!, 0.001)
        assertEquals(6.50, delivery.netPay!!, 0.001)
        assertEquals(18.0, delivery.estMinutes!!, 0.001)
    }

    @Test
    fun `#526 D5b - a PICKUP_CONFIRMED for a displaced pickup does not wipe the live second-pickup card`() {
        // The stacked-pickup Bug10a confirm (D5) can fold while the SECOND pickup is the live one
        // (out-of-order replay). The mismatch branch must NOT null the live openPickup — that
        // blanked the HUD and duplicated a completed card.
        val events = listOf(
            event(AppEventType.DASH_START,
                SessionStartPayload("s1", "DoorDash", 1000L, "interaction", "WaitingForOffer"), 1000L),
            event(AppEventType.PICKUP_NAV_STARTED, pickupPayload("T-A", "J1", "Bill Miller BBQ", 2000L), 2000L),
            // Second pickup opens (closes A into completed, opens B as the live card).
            event(AppEventType.PICKUP_NAV_STARTED, pickupPayload("T-B", "J1", "Mama Margies", 3000L), 3000L),
            // The displaced first pickup (A) is confirmed while B is live — the mismatch branch.
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T-A", "J1", "Bill Miller BBQ", 2000L, arrived = 2500L, confirmed = 3000L), 3000L),
            // Flush the live card so we can observe it survived.
            event(AppEventType.DASH_STOP, SessionStopPayload(
                sessionId = "s1", endedAt = 4000L, source = "test",
            ), 4000L),
        )
        val cards = FlowCardMapper.fold(events)
        val pickups = cards.filterIsInstance<FlowCardSnapshot.Pickup>()
        assertTrue(
            "the live second pickup (Mama Margies) must survive the displaced-A confirm",
            pickups.any { it.storeName == "Mama Margies" },
        )
        assertTrue("the first pickup (Bill Miller) is present too", pickups.any { it.storeName == "Bill Miller BBQ" })
        assertEquals("no duplicate pickup cards after id-dedup", pickups.size, pickups.distinctBy { it.taskId }.size)
    }

    @Test
    fun `#526 FIX4 - a double PICKUP_CONFIRMED for one task produces ONE completed pickup card`() {
        // Recovery-replay / close-out-then-edge can re-fire the confirm for a task whose card already
        // closed. The mismatch branch must skip synthesizing a second completed card.
        val events = listOf(
            event(AppEventType.PICKUP_NAV_STARTED, pickupPayload("T1", "J1", "Wendy's", 1000L), 1000L),
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T1", "J1", "Wendy's", 1000L, arrived = 1500L, confirmed = 2000L), 2000L),
            // Same task confirmed AGAIN after its card already closed (openPickup is now null).
            event(AppEventType.PICKUP_CONFIRMED,
                pickupPayload("T1", "J1", "Wendy's", 1000L, arrived = 1500L, confirmed = 2000L), 2000L),
        )
        val cards = FlowCardMapper.fold(events)
        val pickups = cards.filterIsInstance<FlowCardSnapshot.Pickup>()
        assertEquals("one completed pickup card despite the double-confirm", 1, pickups.size)
        assertEquals(cards.map { it.id }.distinct().size, cards.size)
    }
}
