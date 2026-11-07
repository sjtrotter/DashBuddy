package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.ManualDeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1516: the fold records an offer's scope and quote basis and a drop's job offer hashes. */
class TimelineColumnFoldTest {
    private var seq = 0L

    private fun ev(type: AppEventType, at: Long, payload: AppEventPayload) = SequencedAppEvent(
        sequenceId = ++seq,
        event = AppEvent(type = type, occurredAt = at, sessionId = "S", payload = payload),
    )

    private val ctx = RecordFolds.foldEvent(
        ev(AppEventType.DASH_START, 0, SessionStartPayload("S", Platform.DoorDash.name, 0, SessionStartSource.INTERACTION, "x")),
        null, 0.3,
    ).context

    private fun offer(scope: OfferScope?, basis: OfferQuoteBasis) = RecordFolds.foldEvent(
        ev(
            AppEventType.OFFER_DECLINED, 10,
            OfferPayload(
                offerHash = "h", parsedOffer = ParsedOffer(offerHash = "h", quoteBasis = basis, offerScope = scope),
                evaluation = null, outcome = AppEventType.OFFER_DECLINED, presentedAt = 1, decidedAt = 10,
                returnFlow = Flow.Idle,
            ),
        ),
        ctx, 0.3,
    ).offer!!

    @Test fun `offer scope and basis are recorded, an old payload's scope from its basis`() {
        assertEquals("ADD_ON" to "INCREMENTAL", offer(OfferScope.ADD_ON, OfferQuoteBasis.INCREMENTAL).let { it.offerScope to it.quoteBasis })
        assertEquals("ADD_ON" to "TOTAL", offer(OfferScope.ADD_ON, OfferQuoteBasis.TOTAL).let { it.offerScope to it.quoteBasis })
        assertEquals("STANDALONE" to "TOTAL", offer(null, OfferQuoteBasis.TOTAL).let { it.offerScope to it.quoteBasis })
        assertEquals("ADD_ON" to "INCREMENTAL", offer(null, OfferQuoteBasis.INCREMENTAL).let { it.offerScope to it.quoteBasis })
    }

    @Test fun `a drop records its job's distinct offer hashes, none when blank or absent`() {
        for ((hashes, expected) in listOf(
            listOf("a", "b", "a") to listOf("a", "b"),
            listOf("a") to listOf("a"),
            emptyList<String>() to null,
            listOf("a", " ") to null,
        )) {
            val drop = RecordFolds.foldEvent(
                ev(AppEventType.DELIVERY_COMPLETED, 100, DeliveryPayload(jobId = "J", taskId = "T", phaseStartedAt = 50, completedAt = 100, jobOfferHashes = hashes)),
                ctx, 0.3,
            ).delivery!!
            assertEquals(hashes.toString(), expected, drop.jobOfferHashes)
        }
    }

    @Test fun `a manual delivery records no offer hashes`() {
        val manual = RecordFolds.foldEvent(
            ev(AppEventType.MANUAL_DELIVERY, 200, ManualDeliveryPayload(sessionId = "S", storeName = "M", pay = 8.0, completedAt = 200)),
            ctx, 0.3,
        ).delivery!!
        assertNull(manual.jobOfferHashes)
    }
}
