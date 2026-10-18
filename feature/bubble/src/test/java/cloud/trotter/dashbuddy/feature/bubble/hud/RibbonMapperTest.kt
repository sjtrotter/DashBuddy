package cloud.trotter.dashbuddy.feature.bubble.hud

import org.junit.Test
import org.junit.Assert.*
import cloud.trotter.dashbuddy.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.dashbuddy.domain.format.Formats
import cloud.trotter.dashbuddy.domain.analytics.SessionRecord
import cloud.trotter.dashbuddy.domain.analytics.SessionNet
import cloud.trotter.dashbuddy.domain.state.*
import cloud.trotter.dashbuddy.domain.model.cards.*
import cloud.trotter.dashbuddy.domain.model.event.AppEventType

class RibbonMapperTest {
    private fun await(start: Long = 100, end: Long? = 200, id: String = "a") = FlowCardSnapshot.Awaiting(id, start, end)
    private fun pickup(start: Long = 100, end: Long? = 200) = FlowCardSnapshot.Pickup(start, end, "p", "j", "Store")
    private fun delivery(start: Long = 200, end: Long? = 300) = FlowCardSnapshot.Delivery(start, end, "d", "j")
    private fun offer(outcome: AppEventType? = null, start: Long = 100) = FlowCardSnapshot.Offer(start, start + 100, "o$start", outcome = outcome)
    private fun map(vararg cards: FlowCardSnapshot) = RibbonMapper.map(CardStack(cards.toList()), 100)

    @Test
    fun `empty stack gives no segments and no marks`() {
        val model = RibbonMapper.map(CardStack.Empty, 100)
        assertTrue(model.segments.isEmpty())
        assertTrue(model.marks.isEmpty())
        assertNull(model.tail)
    }

    @Test
    fun `awaiting maps to wait`() {
        assertEquals(listOf(RibbonSegment(RibbonKind.WAIT, 100, 200)), map(await()).segments)
    }

    @Test
    fun `pickup and delivery map to pickup and dropoff`() {
        assertEquals(listOf(RibbonKind.PICKUP, RibbonKind.DROPOFF), map(pickup(), delivery()).segments.map { it.kind })
    }

    @Test
    fun `offer time counts as waiting`() {
        assertEquals(RibbonKind.WAIT, map(offer()).segments.single().kind)
    }

    @Test
    fun `accepted offer gives a filled mark at presentation`() {
        assertEquals(listOf(OfferMark(100, true)), map(offer(AppEventType.OFFER_ACCEPTED)).marks)
    }

    @Test
    fun `declined and timed out offers give hollow marks`() {
        assertEquals(listOf(OfferMark(100, false), OfferMark(300, false)),
            map(offer(AppEventType.OFFER_DECLINED), offer(AppEventType.OFFER_TIMEOUT, 300)).marks)
    }

    @Test
    fun `undecided offer gives no mark`() {
        assertTrue(map(offer()).marks.isEmpty())
    }

    @Test
    fun `post task time counts as waiting`() {
        val card = FlowCardSnapshot.PostTask(100, 200, "j")
        assertEquals(RibbonKind.WAIT, map(card).segments.single().kind)
    }

    @Test
    fun `active card is the open live tail`() {
        val model = RibbonMapper.map(CardStack(active = pickup(100, null)), 100)
        assertEquals(RibbonKind.PICKUP, model.tail)
        assertEquals(RibbonSegment(RibbonKind.PICKUP, 100, null), model.segments.single())
    }

    @Test
    fun `cards before the dash start are clipped`() {
        val model = map(await(0, 50), pickup(50, 200))
        assertEquals(listOf(RibbonSegment(RibbonKind.PICKUP, 100, 200)), model.segments)
    }

    @Test
    fun `overlapping cards never overlap after mapping`() {
        val model = map(delivery(150, 350), pickup(100, 200))
        assertEquals(listOf(RibbonSegment(RibbonKind.PICKUP, 100, 200), RibbonSegment(RibbonKind.DROPOFF, 200, 350)), model.segments)
    }

    @Test
    fun `adjacent waits merge`() {
        assertEquals(listOf(RibbonSegment(RibbonKind.WAIT, 100, 300)),
            map(await(), FlowCardSnapshot.PostTask(200, 300, "j")).segments)
    }

    @Test
    fun `at door twin is not drawn twice`() {
        val active = delivery(200, null)
        val model = RibbonMapper.map(CardStack.of(listOf(pickup(), delivery()), active), 100)
        assertEquals(2, model.segments.size)
        assertEquals(RibbonSegment(RibbonKind.DROPOFF, 200, null), model.segments.last())
    }

    @Test
    fun `an add-on offer decided mid-pickup does not cut the pickup`() {
        val active = pickup(100, null)
        val model = RibbonMapper.map(CardStack.of(listOf(offer(AppEventType.OFFER_ACCEPTED, 500)), active), 100)
        assertEquals(listOf(RibbonSegment(RibbonKind.PICKUP, 100, null)), model.segments)
        assertEquals(listOf(OfferMark(500, true)), model.marks)
    }

    @Test
    fun `a wait is cut around a job it overlaps`() {
        val segments = map(await(100, 600), pickup(200, 400)).segments
        assertEquals(listOf(RibbonSegment(RibbonKind.WAIT, 100, 200), RibbonSegment(RibbonKind.PICKUP, 200, 400),
            RibbonSegment(RibbonKind.WAIT, 400, 600)), segments)
    }

    @Test
    fun `an active wait overlapping a completed wait is one open wait`() {
        val model = RibbonMapper.map(CardStack.of(listOf(await(50, 200)), await(150, null, "live")), 100)
        assertEquals(listOf(RibbonSegment(RibbonKind.WAIT, 100, null)), model.segments)
    }

    @Test
    fun `an open job absorbs a job card that starts after it`() {
        val model = RibbonMapper.map(CardStack.of(listOf(delivery(200, 300)), pickup(100, null)), 100)
        assertEquals(listOf(RibbonSegment(RibbonKind.PICKUP, 100, null)), model.segments)
    }
}
