package cloud.trotter.log.gig.domain.offer.story

import cloud.trotter.log.gig.domain.evaluation.*
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.*
import org.junit.Test

class OfferStoriesTest {
    @Test fun `total uses the landed evaluation without evaluating again`() {
        val s = OfferStories.build(OfferFixtures.pending(StoryFixtures.offer, StoryFixtures.evaluation),
            null, StoryFixtures.config, Platform.DoorDash) { _, _ -> error("TOTAL must not re-evaluate") }
        assertEquals(StoryBasis.TOTAL, s.basis)
        assertEquals("DoorDash", s.appName)
        assertEquals(22.48, s.fact(FactKind.NET)!!.value!!, 0.0)
        assertEquals(12.9, s.fact(FactKind.MILES)!!.value!!, 0.0)
        assertTrue(s.missing.isEmpty())
    }
    @Test fun `unknown pay distance and evaluation never expose evaluator placeholders`() {
        val stories = listOf(StoryFixtures.payUnknown() to Missing.PAY,
            StoryFixtures.distanceUnknown() to Missing.DISTANCE,
            OfferStories.build(OfferFixtures.pending(e = null), null, null, Platform.DoorDash) to Missing.ESTIMATE)
        for ((s, missing) in stories) {
            assertTrue(missing in s.missing)
            listOf(FactKind.NET, FactKind.MILES, FactKind.MINUTES, FactKind.PER_MILE, FactKind.PER_ITEM, FactKind.PER_HOUR)
                .forEach { assertNull("$missing must not invent $it", s.fact(it)?.value) }
        }
    }
    @Test fun `job story retains both totals and quoted deltas for either policy`() {
        val s = StoryFixtures.addOn()
        assertEquals(StoryBasis.ADD_ON_JOB, s.basis)
        assertEquals("Uber", s.appName)
        assertEquals(30.1, s.fact(FactKind.NET)!!.value!!, 0.0)
        assertEquals(24.0, s.fact(FactKind.NET)!!.previous!!, 0.0)
        assertEquals(6.1, s.fact(FactKind.NET)!!.delta!!, 0.00001)
        assertEquals(6.5, s.added.first { it.kind == FactKind.PAY }.value!!, 0.0)
        assertSame(s, s.forPresentation(AddOnSpeech.JOB_TOTALS))
        val delta = s.forPresentation(AddOnSpeech.DELTAS_ONLY)
        assertEquals(StoryBasis.ADD_ON_DELTA, delta.basis)
        assertEquals(s.added, delta.facts)
        assertNull(delta.fact(FactKind.NET))
        assertEquals(StoryBasis.ADD_ON_DELTA, StoryFixtures.addOn(false).basis)
        assertEquals(s.added, StoryFixtures.addOn(false).facts)
    }
    @Test fun `a total-basis add-on carries no added figures`() {
        val o = OfferFixtures.offer().copy(offerScope = OfferScope.ADD_ON, quoteBasis = OfferQuoteBasis.TOTAL)
        val s = OfferStories.build(OfferFixtures.pending(o), null, OfferFixtures.config, Platform.DoorDash)
        assertTrue(s.addOn)
        assertEquals(StoryBasis.TOTAL, s.basis)
        assertTrue(s.added.isEmpty())
    }
    @Test fun `all rule hits and requote survive independently of evaluator verdict`() {
        val s = OfferStories.build(OfferFixtures.pending(), null,
            OfferFixtures.config.copy(allowShopping = false, protectStatsMode = true), Platform.DoorDash, requote = true)
        assertEquals(listOf(RuleHit.SHOPPING_OFF, RuleHit.PROTECT_RATE), s.rules)
        assertTrue(s.requote)
    }
    @Test fun `comparisons use display precision including equality`() {
        val config = EvaluationConfig(rules = listOf(
            ScoringRule.MetricRule("mile", metricType = MetricType.DOLLAR_PER_MILE, targetValue = 20f),
            ScoringRule.MetricRule("hour", metricType = MetricType.ACTIVE_HOURLY, targetValue = 20f)))
        val e = StoryFixtures.evaluation.copy(dollarsPerMile = 19.996, dollarsPerHour = 19.6)
        val s = OfferStories.build(OfferFixtures.pending(StoryFixtures.offer, e), null, config, Platform.Uber)
        assertEquals(Versus.ABOVE, s.fact(FactKind.PER_MILE)!!.versus)
        assertEquals(Versus.ABOVE, s.fact(FactKind.PER_HOUR)!!.versus)
        val below = OfferStories.build(OfferFixtures.pending(StoryFixtures.offer,
            e.copy(dollarsPerMile = 19.99, dollarsPerHour = 19.4)), null, config, Platform.Uber)
        assertEquals(Versus.BELOW, below.fact(FactKind.PER_MILE)!!.versus)
        assertEquals(Versus.BELOW, below.fact(FactKind.PER_HOUR)!!.versus)
    }
    private fun total(config: EvaluationConfig, e: OfferEvaluation? = StoryFixtures.evaluation,
        offer: ParsedOffer = StoryFixtures.offer) =
        OfferStories.build(OfferFixtures.pending(offer, e), null, config, Platform.DoorDash)
    @Test fun `net limit is judged on the net as displayed`() {
        val config = StoryFixtures.limitConfig(net = 7f)
        assertEquals("6.995 displays as 7.00", emptyList<LimitHit>(),
            total(config, StoryFixtures.evaluation.copy(netPayAmount = 6.995)).limits)
        assertEquals(listOf(LimitHit(LimitKind.NET, 7.0)),
            total(config, StoryFixtures.evaluation.copy(netPayAmount = 6.994)).limits)
    }
    @Test fun `distance limit is judged on the miles as displayed`() {
        val config = StoryFixtures.limitConfig(miles = 10f)
        assertEquals("10.04 displays as 10.0", emptyList<LimitHit>(),
            total(config, StoryFixtures.evaluation.copy(distanceMiles = 10.04)).limits)
        assertEquals(listOf(LimitHit(LimitKind.DISTANCE, 10.0)),
            total(config, StoryFixtures.evaluation.copy(distanceMiles = 10.06)).limits)
    }
    @Test fun `items limit applies to shopping offers only`() {
        val config = StoryFixtures.limitConfig(items = 30f)
        val shop = StoryFixtures.offer.copy(itemCount = 31, orders = listOf(OfferFixtures.order("H-E-B", 31)))
        assertEquals(listOf(LimitHit(LimitKind.ITEMS, 30.0)), total(config, offer = shop).limits)
        assertEquals(emptyList<LimitHit>(), total(config, offer = shop.copy(itemCount = 30)).limits)
        val pickup = shop.copy(itemCount = 40, orders = listOf(OfferFixtures.order("H-E-B", 40, shop = false)))
        assertEquals(emptyList<LimitHit>(), total(config, offer = pickup).limits)
    }
    @Test fun `limits come in net distance items order`() {
        val config = StoryFixtures.limitConfig(net = 30f, miles = 10f, items = 10f)
        assertEquals(listOf(LimitKind.NET, LimitKind.DISTANCE, LimitKind.ITEMS), total(config).limits.map { it.kind })
    }
    @Test fun `an unknown figure or a disabled rule never hits`() {
        assertEquals(emptyList<LimitHit>(), total(StoryFixtures.limitConfig(net = 30f, miles = 1f), e = null).limits)
        assertEquals(emptyList<LimitHit>(), total(StoryFixtures.limitConfig(net = 30f, miles = 1f),
            StoryFixtures.evaluation.copy(distanceMiles = 0.0), StoryFixtures.offer.copy(distanceMiles = null)).limits)
        assertEquals(emptyList<LimitHit>(),
            total(StoryFixtures.limitConfig(net = 30f, miles = 1f, items = 1f, enabled = false)).limits)
    }
    @Test fun `add on limits are judged on the job figures and dropped with them`() {
        val config = StoryFixtures.limitConfig(net = 31f, miles = 10f)
        val s = StoryFixtures.addOn(config = config)
        assertEquals(StoryBasis.ADD_ON_JOB, s.basis)
        assertEquals("job net 30.10 and job miles 15.2, not the added 3.1",
            listOf(LimitHit(LimitKind.NET, 31.0), LimitHit(LimitKind.DISTANCE, 10.0)), s.limits)
        assertEquals(emptyList<LimitHit>(), s.forPresentation(AddOnSpeech.DELTAS_ONLY).limits)
        assertEquals(s.limits, s.forPresentation(AddOnSpeech.JOB_TOTALS).limits)
        assertEquals(emptyList<LimitHit>(), StoryFixtures.addOn(jobKnown = false, config = config).limits)
    }
    @Test fun `preferences put store and shopping rules first and protect last`() {
        val s = StoryFixtures.total().copy(
            rules = listOf(RuleHit.SHOPPING_OFF, RuleHit.BLOCKED_STORE, RuleHit.PROTECT_RATE),
            limits = listOf(LimitHit(LimitKind.DISTANCE, 10.0), LimitHit(LimitKind.NET, 7.0)))
        assertEquals(listOf(PreferenceHit.Rule(RuleHit.BLOCKED_STORE), PreferenceHit.Rule(RuleHit.SHOPPING_OFF),
            PreferenceHit.Limit(LimitHit(LimitKind.NET, 7.0)), PreferenceHit.Limit(LimitHit(LimitKind.DISTANCE, 10.0)),
            PreferenceHit.Rule(RuleHit.PROTECT_RATE)), s.preferences())
    }
    @Test fun `registry identity never changes the economics path`() {
        for (platform in listOf(Platform.DoorDash, Platform.Uber)) {
            assertEquals(StoryFixtures.total().facts, StoryFixtures.total(platform).facts)
            assertEquals(platform.appName, StoryFixtures.total(platform).appName)
            val p = OfferFixtures.pending(StoryFixtures.addOn)
            val raw = OfferStories.build(p, null, StoryFixtures.config, platform)
            assertEquals(StoryBasis.ADD_ON_DELTA, raw.basis)
            assertEquals(6.5, raw.fact(FactKind.PAY)!!.value!!, 0.0)
        }
    }
}
