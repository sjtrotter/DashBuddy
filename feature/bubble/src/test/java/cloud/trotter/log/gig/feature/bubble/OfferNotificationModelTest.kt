package cloud.trotter.log.gig.feature.bubble

import cloud.trotter.log.gig.domain.offer.story.OfferFixtures
import cloud.trotter.log.gig.feature.bubble.hud.mapOffer
import cloud.trotter.log.gig.core.designsystem.text.EMPTY_VALUE
import cloud.trotter.log.gig.feature.bubble.hud.*
import cloud.trotter.log.gig.feature.bubble.notification.OfferNotificationModel
import org.junit.Assert.*
import org.junit.Test

class OfferNotificationModelTest {
    private val fixture = OfferFixtures
    private fun map(pending: cloud.trotter.log.gig.domain.state.PendingOffer = fixture.pending()) =
        mapOffer(pending, null, fixture.config)

    @Test fun `standard preserves head bar and all three formatted rates in order with tones`() {
        val original = map() as OfferCardModel.Standard
        val card = original.copy(gauges = original.gauges.zip(GaugeTone.entries).map { (gauge, tone) -> gauge.copy(tone = tone) })
        val model = OfferNotificationModel.from(card)
        assertEquals(card.head.store, model.store)
        assertEquals(card.head.pay, model.pay)
        assertEquals(card.head.expiresAt, model.expiresAt)
        assertEquals(card.head.store, model.store)
        assertEquals(card.bar, model.bar)
        assertEquals(card.gauges.map { it.rateText }, model.rates.map { it.value })
        assertEquals(card.gauges.map { it.tone }, model.rates.map { it.tone })
        assertEquals(listOf("/hr", "/mi", "/ea"), model.rates.map { it.unit })
        assertNull(model.addOnFigures)
    }

    @Test fun `combined preserves the combined figures`() {
        val card = mapOffer(fixture.pending(fixture.addon()), fixture.job(), fixture.config)
        assertTrue(card is OfferCardModel.Combined)
        val model = OfferNotificationModel.from(card)
        assertEquals(card.head.pay, model.pay)
        assertEquals((card as OfferCardModel.Combined).gauges.map { it.rateText }, model.rates.map { it.value })
        assertEquals(card.bar, model.bar)
        assertEquals(card.head.store, model.store)
    }

    @Test fun `distance unknown keeps the HUD em dash hour and mile values`() {
        val card = map(fixture.pending(fixture.offer().copy(distanceMiles = null))) as OfferCardModel.Standard
        val model = OfferNotificationModel.from(card)
        assertEquals(PayBarModel.DistanceUnknown, model.bar)
        assertEquals(listOf(EMPTY_VALUE, EMPTY_VALUE), model.rates.take(2).map { it.value })
        assertEquals(card.gauges.take(2).map { it.rateText }, model.rates.take(2).map { it.value })
        assertTrue(model.rates.all { it.tone == GaugeTone.NEUTRAL })
    }

    @Test fun `raw add on has only delta figures and no bar or rates`() {
        val model = OfferNotificationModel.from(map(fixture.pending(fixture.addon())))
        assertNull(model.addOnFigures?.takeIf { it.contains("score", ignoreCase = true) })
        assertNull(model.bar)
        assertTrue(model.rates.isEmpty())
        val figures = requireNotNull(model.addOnFigures)
        assertTrue(figures.startsWith("+12 min · +"))
        assertTrue(figures.endsWith(" mi · +2 stops · +15 items"))
    }
}
