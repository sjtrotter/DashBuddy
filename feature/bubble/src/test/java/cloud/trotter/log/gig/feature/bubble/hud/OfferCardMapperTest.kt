package cloud.trotter.log.gig.feature.bubble.hud

import org.junit.Assert.*
import org.junit.Test

import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.domain.evaluation.*
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.offer.*
import cloud.trotter.log.gig.domain.model.order.CountUnit
import cloud.trotter.log.gig.domain.state.*
import org.junit.After
import org.junit.Before
import java.util.Locale

class OfferCardMapperTest {
    private val locale = Locale.getDefault()
    @Before fun setup() { Locale.setDefault(Locale.US) }
    @After fun cleanup() { Locale.setDefault(locale) }
    private val f = OfferFixtures
    private val p = f.pending()
    private val job = f.job()
    private val addon = f.addon()
    private val completed = listOf(FlowCardSnapshot.Offer(0, 1, "before", evaluationScore = 60.0,
        outcome = AppEventType.OFFER_ACCEPTED))
    private fun map(pending: PendingOffer = p, active: Job? = job, config: EvaluationConfig? = f.config,
        history: List<FlowCardSnapshot> = completed) = OfferCardMapper.map(pending, active, history, config)
    private fun combined() = map(f.pending(addon)) as OfferCardModel.Combined
    @Test fun `total quote gives the standard card`() {
        assertTrue(map() is OfferCardModel.Standard)
    }
    @Test fun `standard hero is the gross pay`() {
        assertEquals(11.75, map().head.pay!!, 0.0)
    }
    @Test fun `no distance gives distance unknown and em dash rates`() {
        val o = f.offer().copy(distanceMiles = null)
        val m = map(f.pending(o)) as OfferCardModel.Standard
        assertEquals(PayBarModel.DistanceUnknown, m.bar)
        assertNull(m.ring.score)
        assertTrue(m.gauges.all { it.rateText == EMPTY_VALUE })
    }
    @Test fun `evaluation pending gives an unscored bar and an empty ring`() {
        val m = map(f.pending(e = null)) as OfferCardModel.Standard
        assertEquals(PayBarModel.Unscored, m.bar)
        assertNull(m.ring.score)
        assertTrue(m.gauges.all { it.rateText == EMPTY_VALUE })
    }
    @Test fun `scored offer carries the ring score`() {
        assertEquals(74, (map() as OfferCardModel.Standard).ring.score)
    }
    @Test fun `countdown anchor is the pending countdown expiry`() {
        assertEquals(p.countdownExpiresAt, map().head.expiresAt)
        assertNull(map(p.copy(countdownExpiresAt = null)).head.expiresAt)
    }
    @Test fun `badges keep the snapshot order`() {
        val o = f.offer().copy(badges = linkedSetOf(OfferBadge.HIGH_PAYING, OfferBadge.INCLUDES_ALCOHOL))
        assertEquals(listOf(OfferBadgeModel.Platform(OfferBadge.HIGH_PAYING),
            OfferBadgeModel.Platform(OfferBadge.INCLUDES_ALCOHOL), OfferBadgeModel.Shop), map(f.pending(o)).badges)
        assertEquals("High paying", OfferBadge.HIGH_PAYING.displayName)
    }
    @Test fun `add on scope with a total quote is standard with the add on chip`() {
        val m = map(f.pending(f.offer().copy(offerScope = OfferScope.ADD_ON)))
        assertTrue(m is OfferCardModel.Standard)
        assertEquals(OfferBadgeModel.AddOn, m.badges.last())
    }
    @Test fun `incremental quote with a scored job gives the combined card`() {
        assertTrue(map(f.pending(addon)) is OfferCardModel.Combined)
    }
    @Test fun `combined hero is the summed pay with the added delta`() {
        assertEquals(18.75, combined().head.pay!!, 0.0)
        assertEquals(7.0, combined().head.addedPay!!, 0.0)
    }
    @Test fun `combined ring is the re evaluated score with the was score`() {
        val e = OfferEvaluator().evaluate(CombinedOffer.build(job, addon)!!, f.config.forPlatform(p.platform))
        assertEquals(e.score.toInt(), combined().ring.score)
        assertEquals(60, combined().ring.was)
    }
    @Test fun `combined has no was score when the accepted card has none`() {
        assertNull((map(f.pending(addon), history = emptyList()) as OfferCardModel.Combined).ring.was)
        assertNull((map(f.pending(addon), history = listOf(
            FlowCardSnapshot.Offer(0, 1, "before", outcome = AppEventType.OFFER_ACCEPTED))) as OfferCardModel.Combined).ring.was)
        assertNull((map(f.pending(addon), history = listOf(
            FlowCardSnapshot.Offer(0, 1, "before", evaluationScore = 60.0,
                outcome = AppEventType.OFFER_DECLINED))) as OfferCardModel.Combined).ring.was)
        val two = job.copy(acceptedOffers = job.acceptedOffers + job.acceptedOffers)
        assertNull((map(f.pending(addon), active = two) as OfferCardModel.Combined).ring.was)
    }
    @Test fun `combined gauges carry deltas and previous rates`() {
        val m = combined()
        val e = OfferEvaluator().evaluate(CombinedOffer.build(job, addon)!!, f.config.forPlatform(p.platform))
        val before = OfferEvaluator().evaluate(CombinedOffer.quote(job)!!.beforeOffer(addon.offerHash), f.config.forPlatform(p.platform))
        val hour = m.gauges.first()
        assertEquals(GaugeMapper.map(GaugeKind.HOUR, e.estimatedTimeMinutes, e.dollarsPerHour, 18.0,
            before.dollarsPerHour, e.estimatedTimeMinutes - before.estimatedTimeMinutes), hour)
        assertEquals("+2.1", m.gauges[1].deltaText)
        assertEquals("+3", m.gauges[2].deltaText)
        assertTrue(m.gauges.all { it.ghostAt != null })
    }
    @Test fun `combined trip marks the appended nodes`() {
        assertTrue(combined().trip.nodes.take(3).none { it.added })
        assertTrue(combined().trip.nodes.drop(3).all { it.added })
    }
    @Test fun `incremental quote without a job falls back`() {
        assertTrue(map(f.pending(addon), active = null) is OfferCardModel.AddOnRaw)
    }
    @Test fun `job with an unscored accept falls back`() {
        val a = f.accept()
        listOf(a.copy(netPay = null), a.copy(estMinutes = null), a.copy(distanceMiles = null), a.copy(payAmount = null)).forEach {
            assertTrue(map(f.pending(addon), active = job.copy(acceptedOffers = listOf(a, it))) is OfferCardModel.AddOnRaw)
        }
    }
    @Test fun `job holding an add on falls back`() {
        val a = f.accept().copy(quoteBasis = OfferQuoteBasis.INCREMENTAL)
        assertTrue(map(f.pending(addon), active = job.copy(acceptedOffers = job.acceptedOffers + a)) is OfferCardModel.AddOnRaw)
    }
    @Test fun `incremental quote without a distance falls back`() {
        listOf(null, 0.0, -1.0).forEach { distance ->
            assertTrue(map(f.pending(addon.copy(distanceMiles = distance))) is OfferCardModel.AddOnRaw)
        }
    }
    @Test fun `no evaluation config falls back`() {
        assertTrue(map(f.pending(addon), config = null) is OfferCardModel.AddOnRaw)
    }
    @Test fun `job without accepts falls back`() {
        assertTrue(map(f.pending(addon), active = job.copy(acceptedOffers = emptyList())) is OfferCardModel.AddOnRaw)
    }
    @Test fun `fallback carries raw deltas and no ring bar or gauges`() {
        val m = map(f.pending(addon), active = null) as OfferCardModel.AddOnRaw
        assertEquals(DeltaTiles(12, 2.1, 2, 15), m.tiles)
        assertEquals(7.0, m.head.pay!!, 0.0)
        assertEquals(OfferBadgeModel.AddOn, m.badges.last())
    }
    @Test fun `non shop offer has no item gauge`() {
        val o = f.offer().copy(orders = listOf(f.order(shop = false)))
        val m = map(f.pending(o)) as OfferCardModel.Standard
        assertEquals(listOf(GaugeKind.HOUR, GaugeKind.MILE), m.gauges.map { it.kind })
    }
    @Test fun `incremental quote without pay falls back`() {
        assertTrue(map(f.pending(addon.copy(payAmount = null))) is OfferCardModel.AddOnRaw)
    }
    @Test fun `combined evaluation without distance metrics falls back`() {
        val m = OfferCardMapper.map(f.pending(addon), job, completed, f.config) { o, c ->
            OfferEvaluator().evaluate(o.copy(distanceMiles = null), c)
        }
        assertTrue(m is OfferCardModel.AddOnRaw)
    }
    @Test fun `unparsed gross stays unknown`() {
        val m = map(f.pending(f.offer().copy(payAmount = null))) as OfferCardModel.Standard
        assertNull(m.head.pay)
        assertNull(m.ring.score)
        assertEquals(PayBarModel.Unscored, m.bar)
    }
    @Test fun `combined deltas come from two evaluations under one config`() {
        val config = f.config.copy(userEconomy = UserEconomy(gasPricePerGallon = 1.0),
            shopRates = mapOf(p.platform to LearnedShopRate(2.0, UserEconomy.MIN_SHOP_SAMPLES)))
        val calls = mutableListOf<Pair<ParsedOffer, EvaluationConfig>>()
        val m = OfferCardMapper.map(f.pending(addon), job, completed, config) { o, c ->
            calls += o to c
            OfferEvaluator().evaluate(o, c)
        } as OfferCardModel.Combined
        assertEquals(listOf("before:addon", "combined:addon"), calls.map { it.first.offerHash })
        assertSame(calls[0].second, calls[1].second)
        assertEquals(config.forPlatform(p.platform), calls[0].second)
        val before = OfferEvaluator().evaluate(calls[0].first, calls[0].second)
        val after = OfferEvaluator().evaluate(calls[1].first, calls[1].second)
        assertTrue(before.netPayAmount != f.accept().netPay)
        assertTrue(before.estimatedTimeMinutes != f.accept().estMinutes)
        val targets = OfferTargets.from(config, p.platform, true)
        assertEquals(listOf(
            GaugeMapper.map(GaugeKind.HOUR, after.estimatedTimeMinutes, after.dollarsPerHour, targets.perHour,
                before.dollarsPerHour, after.estimatedTimeMinutes - before.estimatedTimeMinutes),
            GaugeMapper.map(GaugeKind.MILE, after.distanceMiles, after.dollarsPerMile, targets.perMile,
                before.dollarsPerMile, after.distanceMiles - before.distanceMiles),
            GaugeMapper.map(GaugeKind.ITEM, 15.0, after.netPayAmount / 15, targets.perItem,
                before.netPayAmount / 12, 3.0)), m.gauges)
        assertEquals(before.payAmount + addon.payAmount!!, m.head.pay!!, 0.0)
        assertEquals(60, m.ring.was)
    }
    @Test fun `shop add on onto a plain pickup job has no previous item rate`() {
        val plain = job.copy(acceptedOffers = listOf(f.accept().copy(isShop = false, quotedItemCount = 1)))
        val m = map(f.pending(addon.copy(orders = listOf(f.order("New", 3)))), active = plain) as OfferCardModel.Combined
        val item = m.gauges.single { it.kind == GaugeKind.ITEM }
        assertNull(item.ghostAt)
        assertEquals("3", item.denominatorText)
        assertTrue(m.gauges.filter { it.kind != GaugeKind.ITEM }.all { it.ghostAt != null })
    }
    @Test fun `mixed units and items fall back`() {
        listOf(false to true, true to false).forEach { (beforeUnits, afterUnits) ->
            val owned = job.copy(acceptedOffers = listOf(f.accept().copy(offerUnitCount = if (beforeUnits) 12 else null)))
            val m = OfferCardMapper.map(f.pending(addon.copy(itemCountIsUnits = afterUnits)), owned, completed, f.config) { _, _ ->
                error("Mixed denominations must not be evaluated as a combined offer")
            }
            assertTrue(m is OfferCardModel.AddOnRaw)
            assertEquals(7.0, m.head.pay!!, 0.0)
        }
    }
    @Test fun `a new order in units on an items quote falls back`() {
        val offer = addon.copy(itemCount = 16, itemCountIsUnits = false, orders = listOf(
            f.order(), f.order("New", 4).copy(countUnit = CountUnit.UNITS)))
        val m = OfferCardMapper.map(f.pending(offer), job, completed, f.config) { _, _ ->
            error("Mixed denominations must not be evaluated as a combined offer")
        }
        assertTrue(m is OfferCardModel.AddOnRaw)
        assertEquals(7.0, m.head.pay!!, 0.0)
        assertEquals(DeltaTiles(12, 2.1, 2, 16), (m as OfferCardModel.AddOnRaw).tiles)
    }
    @Test fun `a count free pickup never trips the unit guard`() {
        val owned = job.copy(acceptedOffers = listOf(f.accept().copy(offerUnitCount = 12)))
        val offer = addon.copy(itemCount = 12, itemCountIsUnits = true, orders = listOf(
            f.order().copy(countUnit = CountUnit.UNITS), f.order("New", 1, shop = false)))
        val evaluated = mutableListOf<ParsedOffer>()
        val model = OfferCardMapper.map(f.pending(offer), owned, completed, f.config) { o, c ->
            evaluated += o
            OfferEvaluator().evaluate(o, c)
        }
        assertTrue(model is OfferCardModel.Combined)
        assertEquals(listOf(12, 12), evaluated.map { it.itemCount })
        assertTrue(evaluated.all { it.itemCountIsUnits })
        val item = (model as OfferCardModel.Combined).gauges.single { it.kind == GaugeKind.ITEM }
        assertEquals("12", item.denominatorText)
        assertEquals("+0", item.deltaText)
    }

}
