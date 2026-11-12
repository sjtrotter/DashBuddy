package cloud.trotter.log.gig.domain.analytics

import cloud.trotter.log.gig.domain.analytics.AnalyticsEventFixtures.acceptedOffer
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventCodec
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.SequencedAppEvent
import cloud.trotter.log.gig.domain.model.event.payload.AppEventPayload
import cloud.trotter.log.gig.domain.model.event.payload.DeclineEvidence
import cloud.trotter.log.gig.domain.model.event.payload.OfferPayload
import cloud.trotter.log.gig.domain.model.event.payload.OfferReceivedPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartPayload
import cloud.trotter.log.gig.domain.model.event.payload.SessionStartSource
import cloud.trotter.log.gig.domain.model.offer.ParsedOffer
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.domain.state.ParsedFields
import cloud.trotter.log.gig.domain.state.PendingOffer
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.state.PlatformRegion
import cloud.trotter.log.gig.domain.state.PoolEntryVia
import cloud.trotter.log.gig.domain.state.PooledOffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1119 S2: `OFFER_NOT_TAKEN` (an offer still in the pool at shift end) in the codec and the fold, and the HUD's live
 * received count. Non-vacuity: on master `77c089dac` the event type, the payload fields and `liveOffersReceived` do not
 * exist, and an `OFFER_NOT_TAKEN` row would decode no payload and fold no offer row.
 */
class OfferNotTakenFoldTest {

    private var seq = 0L

    private fun ev(type: AppEventType, sid: String?, at: Long, payload: AppEventPayload?) =
        SequencedAppEvent(++seq, AppEvent(type = type, occurredAt = at, sessionId = sid, payload = payload))

    private fun notTaken(at: Long, hash: String, tied: List<String>) =
        acceptedOffer(at, evaluation = null, hash = hash).copy(
            outcome = AppEventType.OFFER_NOT_TAKEN,
            offerInstanceId = "doordash:${at - 30}",
            ambiguousJobIds = tied,
            description = "No work at its store by shift end (#1119)",
        )

    @Test fun `OFFER_NOT_TAKEN round-trips with the new fields`() {
        val payload = notTaken(5_000, "h1", listOf("J1", "J2"))
            .copy(linkedJobId = "J3", declineEvidence = DeclineEvidence.SHEET_WINDOW)
        val back = AppEventCodec.decodePayload(AppEventType.OFFER_NOT_TAKEN, AppEventCodec.encodePayload(payload))
        assertEquals(payload, back)
    }

    @Test fun `an old offer row decodes the new fields as defaults`() {
        val old = """{"offerHash":"h1","parsedOffer":{"offerHash":"h1"},"outcome":"OFFER_TIMEOUT",""" +
            """"presentedAt":1,"decidedAt":2,"returnFlow":"Idle"}"""
        val back = AppEventCodec.decodePayload(AppEventType.OFFER_TIMEOUT, old) as OfferPayload
        assertNull(back.offerInstanceId)
        assertNull(back.linkedJobId)
        assertTrue(back.ambiguousJobIds.isEmpty())
        assertNull(back.declineEvidence)
        val received = """{"offerHash":"h1","parsedOffer":{"offerHash":"h1"},"presentedAt":1,"platform":"DoorDash",""" +
            """"returnFlow":"Idle"}"""
        assertNull((AppEventCodec.decodePayload(AppEventType.OFFER_RECEIVED, received) as OfferReceivedPayload).offerInstanceId)
    }

    @Test fun `a not-taken offer folds an offer row with its tied jobs and counts as offered, not declined`() {
        val sid = "S1"
        var ctx: SessionFoldContext? = null
        val outcomes = listOf(
            ev(AppEventType.DASH_START, sid, 1_000,
                SessionStartPayload(sid, Platform.DoorDash.name, startedAt = 1_000, source = SessionStartSource.INTERACTION, startScreen = "x")),
            ev(AppEventType.OFFER_NOT_TAKEN, sid, 9_000, notTaken(5_000, "h1", listOf("J7"))),
            ev(AppEventType.OFFER_NOT_TAKEN, sid, 9_000, notTaken(6_000, "h2", emptyList())),
        ).map { e -> RecordFolds.foldEvent(e, ctx, null).also { ctx = it.context ?: ctx } }
        val row = outcomes[1].offer!!
        assertEquals(AppEventType.OFFER_NOT_TAKEN.name, row.outcome)
        assertEquals(listOf("J7"), row.ambiguousJobIds)
        assertEquals(5_000L, row.decidedAt)
        assertTrue(outcomes[2].offer!!.ambiguousJobIds.isEmpty())
        assertEquals(OfferOutcome.NOT_TAKEN, OfferOutcome.fromEventTypeName(row.outcome))
        val c = ctx!!
        assertEquals(2, c.offersTimeout)
        assertEquals(0, c.offersDeclined)
        assertEquals(2, c.offersReceived)
    }

    @Test fun `the live received count adds the shift's pooled offers to the folded count`() {
        val offer = PendingOffer("h1", ParsedFields.OfferFields(parsedOffer = ParsedOffer(offerHash = "h1")), 1, returnFlow = Flow.Idle)
        fun pooled(sid: String?) = PooledOffer(offer, 2, PoolEntryVia.FRAME, sid, setOf("store a"))
        val region = PlatformRegion(Platform.DoorDash, offerPool = listOf(pooled("S1"), pooled("S1"), pooled("S0")))
        val record = SessionRecord(
            sessionId = "S1", platform = Platform.DoorDash, startedAt = 0, endedAt = null, reportedEarnings = null,
            reportedDurationMillis = null, miles = null, deliveries = 0, jobsCompleted = 0,
            offersReceived = 3, offersAccepted = 1, offersDeclined = 1, offersTimeout = 1,
        )
        assertEquals(5, liveOffersReceived(record, region))
        assertEquals(3, liveOffersReceived(record, null))
        assertNull(liveOffersReceived(null, region))
    }
}
