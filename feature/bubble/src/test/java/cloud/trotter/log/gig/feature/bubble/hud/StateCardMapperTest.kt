package cloud.trotter.log.gig.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

import cloud.trotter.log.gig.domain.model.cards.CardStack
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.pay.ParsedPay
import cloud.trotter.log.gig.domain.model.pay.ParsedPayItem
import cloud.trotter.log.gig.domain.state.*
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.feature.bubble.cards.FlowCardMapper
import cloud.trotter.log.gig.feature.bubble.cards.LiveCardBuilder

class StateCardMapperTest {
    private val pickup = FlowCardSnapshot.Pickup(1_000, taskId = "pickup", jobId = "job", storeName = "Store",
        deadlineMillis = 600_000, netPay = 7.9, distanceMiles = 5.4)
    private val drop = FlowCardSnapshot.Delivery(100_000, taskId = "drop", jobId = "job", storeName = "Store",
        customerHash = "private", deadlineMillis = 900_000, netPay = 8.1, distanceMiles = 4.2)
    private val receipt = FlowCardSnapshot.PostTask(800_000, jobId = "job", storeName = "Store", totalPay = 10.6,
        parsedPay = ParsedPay(listOf(ParsedPayItem("Base pay", 3.5), ParsedPayItem("Peak pay", 1.0)),
            listOf(ParsedPayItem("Store", 4.0), ParsedPayItem("Other", 2.1))))
    private val region = PlatformRegion(Platform.DoorDash, Mode.Online, Session("last", 1_000))
    private fun map(flow: Flow = Flow.Idle, active: FlowCardSnapshot? = null,
        mode: Mode = Mode.Online, stack: CardStack = CardStack.Empty,
        session: cloud.trotter.log.gig.domain.analytics.SessionRecord? = lastDash()) =
        StateCardMapper.map(flow, mode, active, stack, region, session)
    private fun waiting(stack: CardStack = CardStack.Empty) = map(stack = stack) as StateCardModel.Waiting
    private fun offer(hash: String, end: Long?, outcome: AppEventType?) =
        FlowCardSnapshot.Offer(2_000, end, hash, payAmount = 4.25, outcome = outcome)

    @Test fun `idle online gives waiting`() {
        assertEquals(1_000L, waiting().since)
        assertEquals(1_000L, waiting().sessionStartedAt)
    }
    @Test fun `awaiting card keeps waiting while the flow moves first`() {
        val active = FlowCardSnapshot.Awaiting("awaiting", 1_000)
        Flow.entries.forEach { assertEquals(waiting(), map(it, active)) }
        assertNull(map(Flow.Idle, active, Mode.Paused))
    }
    @Test fun `task unassigned gives waiting`() {
        assertTrue(map(Flow.TaskUnassigned) is StateCardModel.Waiting)
    }
    @Test fun `paused gives no card`() {
        assertNull(map(mode = Mode.Paused))
        assertNull(map(Flow.TaskDropoffArrived, drop.copy(arrivedAt = 200_000), Mode.Paused))
    }
    @Test fun `offer presented gives no card`() {
        assertNull(map(Flow.OfferPresented))
        Flow.entries.forEach { assertNull(map(it, offer("active", null, null))) }
    }
    @Test fun `session ended gives no card`() { assertNull(map(Flow.SessionEnded)) }
    @Test fun `pickup navigation anchors at the task start`() {
        val m = map(Flow.TaskPickupNavigation, pickup) as StateCardModel.PickupNav
        assertEquals(1_000L, m.leg.anchorAt)
        assertEquals(JobFacts("Store", 600_000, 7.9, 5.4), m.facts)
    }
    @Test fun `pickup arrived anchors at arrival`() {
        val m = map(Flow.TaskPickupArrived, pickup.copy(arrivedAt = 20_000)) as StateCardModel.PickupArrived
        assertEquals(20_000L, m.leg.anchorAt)
    }
    @Test fun `pickup arrived with item counts gives shopping`() {
        val m = map(Flow.TaskPickupArrived, pickup.copy(arrivedAt = 20_000, itemsShopped = 14,
            itemsRemaining = 8)) as StateCardModel.Shopping
        assertEquals(14, m.shopped)
        assertEquals(22, m.total)
        assertEquals(20_000L, m.arrivedAt)
    }
    @Test fun `zero items total stays the dwell card`() {
        assertTrue(map(Flow.TaskPickupArrived, pickup.copy(arrivedAt = 20_000,
            itemsShopped = 0, itemsRemaining = 0)) is StateCardModel.PickupArrived)
    }
    @Test fun `negative item count stays the dwell card`() {
        listOf(-1 to 8, 14 to -1).forEach { (shopped, remaining) ->
            assertTrue(map(Flow.TaskPickupArrived, pickup.copy(arrivedAt = 20_000,
                itemsShopped = shopped, itemsRemaining = remaining)) is StateCardModel.PickupArrived)
        }
    }
    @Test fun `arrived flow without arrival time falls back to navigation`() {
        assertTrue(map(Flow.TaskPickupArrived, pickup) is StateCardModel.PickupNav)
        assertTrue(map(Flow.TaskDropoffArrived, drop) is StateCardModel.DropoffNav)
        assertNull(map(Flow.TaskPickupArrived))
        assertTrue(map(Flow.TaskDropoffArrived, pickup) is StateCardModel.PickupNav)
    }
    @Test fun `card from the previous frame still renders while the flow moves first`() {
        val card = map(Flow.TaskDropoffArrived, pickup.copy(arrivedAt = 20_000))
        assertTrue(card is StateCardModel.PickupArrived)
        assertEquals(20_000L, (card as StateCardModel.PickupArrived).leg.anchorAt)
        assertTrue(map(Flow.TaskPickupNavigation, pickup.copy(arrivedAt = 20_000)) is StateCardModel.PickupArrived)
        assertTrue(map(Flow.TaskPickupArrived, drop.copy(arrivedAt = 30_000)) is StateCardModel.DropoffArrived)
        assertTrue(map(Flow.TaskDropoffNavigation, drop.copy(arrivedAt = 30_000)) is StateCardModel.DropoffArrived)
    }
    @Test fun `post task card renders the receipt whatever the flow says`() {
        val expected = map(Flow.PostTask, receipt)
        Flow.entries.forEach { assertEquals("flow=$it", expected, map(it, receipt)) }
    }
    @Test fun `dropoff navigation anchors at the dropoff start`() {
        val m = map(Flow.TaskDropoffNavigation, drop) as StateCardModel.DropoffNav
        assertEquals(100_000L, m.leg.anchorAt)
        assertEquals(JobFacts("Store", 900_000, 8.1, 4.2), m.facts)
    }
    @Test fun `dropoff arrived gives the door card and cues cash tip`() {
        val m = map(Flow.TaskDropoffArrived, drop.copy(arrivedAt = 700_000)) as StateCardModel.DropoffArrived
        assertEquals(700_000L, m.leg.anchorAt)
        assertTrue(m.cuesCashTip)
    }
    @Test fun `post task gives the receipt and cues cash tip`() {
        val m = map(Flow.PostTask, receipt) as StateCardModel.Receipt
        assertEquals("Store", m.store)
        assertEquals(800_000L, m.deliveredAt)
        assertTrue(m.cuesCashTip)
    }
    @Test fun `receipt lists platform lines then one tip row then the total`() {
        val m = map(Flow.PostTask, receipt) as StateCardModel.Receipt
        assertEquals(listOf(ReceiptLine("Base pay", 3.5), ReceiptLine("Peak pay", 1.0)), m.lines)
        assertEquals(6.1, m.tip!!, 0.0001)
        assertEquals(10.6, m.total!!, 0.0001)
    }
    @Test fun `receipt without a total shows no zero`() {
        val m = map(Flow.PostTask, receipt.copy(totalPay = null)) as StateCardModel.Receipt
        assertNull(m.total)
        val live = LiveCardBuilder.build(AppState(Regions(
            flow = FlowRegion(Flow.PostTask, activePlatform = Platform.DoorDash),
            platforms = mapOf(Platform.DoorDash to region)))) as FlowCardSnapshot.PostTask
        assertNull(live.totalPay)
        assertNull((map(Flow.PostTask, live) as StateCardModel.Receipt).total)
        val historical = FlowCardMapper.fold(listOf(AppEvent(AppEventType.DELIVERY_COMPLETED, 800_000, "last",
            DeliveryPayload("job", "drop", phaseStartedAt = 700_000, completedAt = 800_000))))
            .filterIsInstance<FlowCardSnapshot.PostTask>().single()
        assertNull(historical.totalPay)
        val unknown = map(Flow.PostTask, receipt.copy(parsedPay = null)) as StateCardModel.Receipt
        assertNull(unknown.tip)
    }
    @Test fun `task active with a pickup task picks the pickup card`() {
        assertTrue(map(Flow.TaskActive, pickup) is StateCardModel.PickupNav)
        assertTrue(map(Flow.TaskActive, pickup.copy(arrivedAt = 20_000)) is StateCardModel.PickupArrived)
        assertTrue(map(Flow.TaskActive, drop.copy(arrivedAt = 20_000)) is StateCardModel.DropoffArrived)
    }
    @Test fun `task active without a leg gives no card`() {
        assertNull(map(Flow.TaskActive))
        assertTrue(map(Flow.TaskActive, receipt) is StateCardModel.Receipt)
    }
    @Test fun `navigation and waiting never cue cash tip`() {
        assertFalse(waiting().cuesCashTip)
        assertFalse(map(Flow.TaskPickupNavigation, pickup)!!.cuesCashTip)
        assertFalse(map(Flow.TaskDropoffNavigation, drop)!!.cuesCashTip)
        assertFalse(map(Flow.TaskPickupArrived, pickup.copy(arrivedAt = 20_000))!!.cuesCashTip)
    }
    @Test fun `delivery model carries no customer field`() {
        val a = map(Flow.TaskDropoffNavigation, drop)
        assertEquals(a, map(Flow.TaskDropoffNavigation, drop.copy(customerHash = "different")))
        assertTrue(a!!.javaClass.declaredFields.none { it.name.contains("customer", ignoreCase = true) })
        assertTrue(JobFacts::class.java.declaredFields.none { it.name.contains("customer", ignoreCase = true) })
    }
    @Test fun `waiting tally reads the live session record`() {
        assertEquals(5, waiting().deliveries)
        assertEquals(5, waiting().accepted)
        assertEquals(8, waiting().received)
    }
    @Test fun `waiting tally from another session is unknown`() {
        val m = map(session = lastDash().copy(sessionId = "other")) as StateCardModel.Waiting
        assertNull(m.deliveries)
        assertNull(m.accepted)
        assertNull(m.received)
    }
    @Test fun `last offer is the latest decided offer`() {
        val m = waiting(CardStack(listOf(offer("new", 6_000, AppEventType.OFFER_DECLINED),
            offer("old", 5_000, AppEventType.OFFER_ACCEPTED))))
        assertEquals(LastOffer(4.25, LastOfferOutcome.DECLINED), m.lastOffer)
    }
    @Test fun `undecided offer is never the last offer`() {
        assertNull(waiting(CardStack(listOf(offer("open", 6_000, null)))).lastOffer)
        val m = waiting(CardStack(listOf(offer("decided", 5_000, AppEventType.OFFER_ACCEPTED),
            offer("open", null, null))))
        assertEquals(LastOfferOutcome.ACCEPTED, m.lastOffer!!.outcome)
    }
    @Test fun `timed out offer reads expired`() {
        assertEquals(LastOfferOutcome.EXPIRED,
            waiting(CardStack(listOf(offer("expired", 5_000, AppEventType.OFFER_TIMEOUT)))).lastOffer!!.outcome)
    }
    @Test fun `receipt keeps a parsed zero`() {
        val zero = map(Flow.PostTask, receipt.copy(totalPay = 0.0)) as StateCardModel.Receipt
        assertEquals(0.0, zero.total!!, 0.0)
    }

    @Test fun `pinned away from the flow's platform shows the region's own waiting`() {
        val card = StateCardMapper.map(Flow.TaskPickupArrived, Mode.Online, null, CardStack.Empty, region, lastDash(), flowOwned = false)
        org.junit.Assert.assertTrue(card is StateCardModel.Waiting)
    }
    @Test fun `pinned away while the region has its own task shows no card`() {
        val busy = region.copy(activeTask = cloud.trotter.log.gig.domain.state.Task("t", "j", cloud.trotter.log.gig.domain.state.TaskPhase.PICKUP, startedAt = 2_000))
        org.junit.Assert.assertNull(StateCardMapper.map(Flow.Idle, Mode.Online, null, CardStack.Empty, busy, lastDash(), flowOwned = false))
    }
}
