package cloud.trotter.log.gig.domain.offer.story

import cloud.trotter.log.gig.domain.evaluation.*
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
