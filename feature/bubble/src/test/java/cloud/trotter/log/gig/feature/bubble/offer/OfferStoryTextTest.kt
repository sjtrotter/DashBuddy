package cloud.trotter.log.gig.feature.bubble.offer

import cloud.trotter.log.gig.domain.offer.story.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en")
class OfferStoryTextTest {
    private val originalLocale = java.util.Locale.getDefault()
    @org.junit.Before fun setLocale() { java.util.Locale.setDefault(java.util.Locale.US) }
    @org.junit.After fun restoreLocale() { java.util.Locale.setDefault(originalLocale) }

    private val res get() = RuntimeEnvironment.getApplication().resources
    @Test fun `total titles and line use the registry and landed facts`() {
        val s = StoryFixtures.total()
        assertEquals("DoorDash offer", OfferStoryText.title(s, res))
        assertEquals("H-E-B · $24.50 pay · $22.48 net · 12.9 mi · 45 min · $22/hr", OfferStoryText.line(s, res))
        assertEquals("Uber offer", OfferStoryText.title(StoryFixtures.total(cloud.trotter.log.gig.domain.state.Platform.Uber), res))
        assertEquals(OfferStoryText.line(s, res), OfferStoryText.line(StoryFixtures.total(requote = true), res))
    }
    @Test fun `unknown inputs are words rather than zero figures`() {
        assertEquals("H-E-B · $12.50 pay · Distance unknown", OfferStoryText.line(StoryFixtures.distanceUnknown(), res))
        assertEquals("H-E-B · Pay unknown", OfferStoryText.line(StoryFixtures.payUnknown(), res))
    }
    @Test fun `add on policy selects the same job or delta facts for text and card`() {
        val s = StoryFixtures.addOn()
        assertEquals("Uber add-on", OfferStoryText.title(s, res))
        assertEquals("H-E-B · +$6.50 pay · Job $30.10 net · 15.2 mi · 58 min · $31/hr",
            OfferStoryText.line(s.forPresentation(AddOnSpeech.JOB_TOTALS), res))
        assertEquals("H-E-B · +$6.50 pay · +3.1 mi · +8 min",
            OfferStoryText.line(s.forPresentation(AddOnSpeech.DELTAS_ONLY), res))
        assertEquals(OfferStoryText.line(s.forPresentation(AddOnSpeech.DELTAS_ONLY), res),
            OfferStoryText.line(StoryFixtures.addOn(false), res))
    }
    @Test fun `rule notes precede figures and take precedence over missing facts`() {
        assertEquals("H-E-B · Blocked store · $24.50 pay · $22.48 net · 12.9 mi · 45 min · $22/hr",
            OfferStoryText.line(StoryFixtures.blocked(), res))
        assertEquals("Blocked store", OfferStoryText.note(StoryFixtures.blocked().copy(missing = setOf(Missing.PAY)), res))
        assertEquals("Pay unknown", OfferStoryText.note(StoryFixtures.payUnknown(), res))
        assertEquals("Distance unknown", OfferStoryText.note(StoryFixtures.distanceUnknown(), res))
    }
    @Test fun `one preference order selects the note and lists every hit`() {
        val all = StoryFixtures.netLimit().copy(rules = listOf(RuleHit.BLOCKED_STORE, RuleHit.PROTECT_RATE))
        assertEquals("Blocked store", OfferStoryText.note(all, res))
        assertEquals("H-E-B · Blocked store · Net under $7.00 · Protect acceptance rate · $24.50 pay · $22.48 net · 12.9 mi · 45 min · $22/hr",
            OfferStoryText.line(all, res))
        val limitAndProtect = StoryFixtures.netLimit().copy(rules = listOf(RuleHit.PROTECT_RATE), missing = setOf(Missing.ESTIMATE))
        assertEquals("Net under $7.00", OfferStoryText.note(limitAndProtect, res))
    }
    @Test fun `limit notes name the limit the dasher set`() {
        val s = StoryFixtures.total().copy(limits = listOf(LimitHit(LimitKind.DISTANCE, 10.0), LimitHit(LimitKind.ITEMS, 30.0)))
        assertEquals("Over 10.0 mi", OfferStoryText.note(s, res))
        assertEquals("H-E-B · Over 10.0 mi · Over 30 items · $24.50 pay · $22.48 net · 12.9 mi · 45 min · $22/hr",
            OfferStoryText.line(s, res))
    }
    @Test fun `loss is a signed net fact`() {
        assertEquals("H-E-B · $24.50 pay · -$3.75 net · 12.9 mi · 45 min · $22/hr",
            OfferStoryText.line(StoryFixtures.loss(), res))
    }
}
