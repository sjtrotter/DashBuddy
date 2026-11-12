package cloud.trotter.log.gig.domain.analytics.checks

import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.receipts.ActiveMatchRow
import cloud.trotter.log.gig.domain.analytics.receipts.SavedReceipt
import cloud.trotter.log.gig.domain.analytics.receipts.ShiftReceipts
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.analytics.timeline.JobState
import cloud.trotter.log.gig.domain.analytics.timeline.ShiftTimeline
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineDelivery
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineOffer
import cloud.trotter.log.gig.domain.model.event.payload.OfferOutcomeResolution
import cloud.trotter.log.gig.domain.model.event.payload.ReceiptMatchRole
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.state.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1517: [ShiftChecks] over the folded timeline, and the timeline's repair states: a recorded unassign, the
 * dasher's attestation and a missed delivery added from the offer.
 */
class ShiftChecksTest {

    private fun session(ended: Boolean = true) = SessionRecord(
        sessionId = "S", platform = Platform.DoorDash, startedAt = 0, endedAt = if (ended) 10_000 else null,
        reportedEarnings = null, reportedDurationMillis = null, miles = null, deliveries = 0, jobsCompleted = 0,
        offersReceived = 0, offersAccepted = 0, offersDeclined = 0, offersTimeout = 0,
    )

    private fun offer(
        seq: Long,
        at: Long = seq * 100,
        hash: String = "h$seq",
        scope: OfferScope = OfferScope.STANDALONE,
        basis: OfferQuoteBasis = OfferQuoteBasis.TOTAL,
        linkedJobId: String? = null,
        resolved: String? = null,
        unassignedAt: Long? = null,
    ) = TimelineOffer(seq, hash, OfferOutcome.ACCEPTED, at, 7.5, 3.0, "Sample Tacos", scope, basis, linkedJobId, resolved, unassignedAt)

    private fun drop(seq: Long, jobId: String, hashes: List<String>?, source: Long? = null, basis: String = PayBasis.DROP_SHARE) =
        TimelineDelivery(seq, jobId, "Sample Tacos", null, seq * 100, 7.5, basis, null, hashes, hashes?.singleOrNull(), source)

    private fun checks(
        offers: List<TimelineOffer>,
        drops: List<TimelineDelivery>,
        ended: Boolean = true,
        money: MoneyCheck? = null,
        receipts: ShiftReceipts = ShiftReceipts.EMPTY,
    ) = ShiftChecks.of(session(ended), ShiftTimeline.fold(offers, emptyList(), drops, running = !ended), money, receipts)

    private fun cards(offers: List<TimelineOffer>, drops: List<TimelineDelivery>) =
        ShiftTimeline.fold(offers, emptyList(), drops, running = false).filterIsInstance<JobCard>()

    @Test fun `an accepted offer with no delivery and no unassign raises one check`() {
        assertEquals(listOf(ShiftCheck.NoDelivery(1)), checks(listOf(offer(1)), emptyList()))
    }

    @Test fun `a recorded unassign raises none and its card reads unassigned at its time`() {
        val unassigned = listOf(offer(1, unassignedAt = 512))
        assertTrue(checks(unassigned, emptyList()).isEmpty())
        val card = cards(unassigned, emptyList()).single()
        assertEquals(JobState.UNASSIGNED, card.state)
        assertEquals(512L, card.unassignedAt)
        assertTrue(card.openOffers.isEmpty())
    }

    @Test fun `the dasher's attestation raises none and its card says so`() {
        val marked = listOf(offer(1, resolved = OfferOutcomeResolution.UNASSIGNED_ATTESTED))
        assertTrue(checks(marked, emptyList()).isEmpty())
        val card = cards(marked, emptyList()).single()
        assertEquals(JobState.MARKED_UNASSIGNED, card.state)
        assertEquals(listOf(1L), card.markedOfferSeqs)
    }

    @Test fun `a stack with two deliveries raises none`() {
        assertTrue(checks(listOf(offer(1)), listOf(drop(2, "J1", listOf("h1")), drop(3, "J1", listOf("h1")))).isEmpty())
    }

    @Test fun `an add-on raises none`() {
        val offers = listOf(offer(1), offer(2, scope = OfferScope.ADD_ON, basis = OfferQuoteBasis.INCREMENTAL))
        val hashes = listOf("h1", "h2")
        assertTrue(checks(offers, listOf(drop(3, "J1", hashes), drop(4, "J1", hashes))).isEmpty())
    }

    @Test fun `a running shift raises none, money included`() {
        val money = MoneyCheck(appTotal = 20.0, deliveryPay = 10.0, deliveryPayEstimated = false)
        assertTrue(checks(listOf(offer(1)), emptyList(), ended = false, money = money).isEmpty())
    }

    @Test fun `two accepts and one delivery on a job is one group, never a pick`() {
        val hashes = listOf("h1", "h2")
        val out = checks(listOf(offer(1), offer(2)), listOf(drop(3, "J1", hashes)))
        assertEquals(listOf(ShiftCheck.MoreAcceptsThanDeliveries("J1", accepted = 2, delivered = 1)), out)
    }

    @Test fun `a resolved offer leaves the group's open count`() {
        val hashes = listOf("h1", "h2")
        val offers = listOf(offer(1), offer(2, resolved = OfferOutcomeResolution.UNASSIGNED_INFERRED))
        assertTrue(checks(offers, listOf(drop(3, "J1", hashes))).isEmpty())
    }

    @Test fun `a differing money check raises one money check`() {
        val money = MoneyCheck(appTotal = 20.0, deliveryPay = 12.5, deliveryPayEstimated = false)
        assertEquals(
            listOf(ShiftCheck.MoneyDiffers(MoneyCheck.Higher.APP_TOTAL, 7.5)),
            checks(emptyList(), emptyList(), money = money),
        )
        assertTrue(checks(emptyList(), emptyList(), money = money.copy(deliveryPay = 20.0)).isEmpty())
    }

    @Test fun `a missed delivery added from an unjoined offer joins its card and clears the check`() {
        val offers = listOf(offer(1))
        val manual = drop(9, "manual:9", hashes = null, source = 1, basis = PayBasis.MANUAL)
        val card = cards(offers, listOf(manual)).single()
        assertEquals("offer:1", card.key)
        assertEquals("manual:9", card.jobId)
        assertEquals(listOf(9L), card.deliveries.map { it.deliveryEventSequenceId })
        assertEquals(JobState.NORMAL, card.state)
        assertTrue(checks(offers, listOf(manual)).isEmpty())
    }

    @Test fun `a missed delivery added from an offer linked to a job with no delivery joins that job's card`() {
        val offers = listOf(offer(1, linkedJobId = "J7"))
        val manual = drop(9, "manual:9", hashes = null, source = 1, basis = PayBasis.MANUAL)
        val card = cards(offers, listOf(manual)).single()
        assertEquals("J7", card.jobId)
        assertEquals(listOf(9L), card.deliveries.map { it.deliveryEventSequenceId })
        assertTrue(checks(offers, listOf(manual)).isEmpty())
    }

    @Test fun `a source naming no accepted offer of the shift stays a card of its own`() {
        val manual = drop(9, "manual:9", hashes = null, source = 77, basis = PayBasis.MANUAL)
        val all = cards(listOf(offer(1)), listOf(manual))
        assertEquals(listOf("offer:1", "job:manual:9"), all.map { it.key })
        assertEquals(listOf(ShiftCheck.NoDelivery(1)), checks(listOf(offer(1)), listOf(manual)))
    }

    // #1119 S2: a tie at a job's store raises one OfferNotMatched per job on an ended shift.
    private fun passed(seq: Long, outcome: OfferOutcome, vararg tied: String) =
        TimelineOffer(seq, "h$seq", outcome, seq * 100, 7.5, 3.0, "Sample Tacos", OfferScope.STANDALONE,
            OfferQuoteBasis.TOTAL, null, ambiguousJobIds = tied.toList())

    @Test fun `tied offers raise one check per job, grouped and sorted, a demoted decline included`() {
        val offers = listOf(
            passed(7, OfferOutcome.NOT_TAKEN, "J2"),
            passed(3, OfferOutcome.NOT_TAKEN, "J1"),
            passed(5, OfferOutcome.DECLINED, "J1"),
            passed(4, OfferOutcome.NOT_TAKEN, "J2"),
            passed(6, OfferOutcome.NOT_TAKEN),
        )
        assertEquals(
            listOf(ShiftCheck.OfferNotMatched("J1", listOf(3L, 5L)), ShiftCheck.OfferNotMatched("J2", listOf(4L, 7L))),
            checks(offers, emptyList()),
        )
    }

    // #1119 review round 1 (S4): a tie candidate that later linked elsewhere still counts in its tie's check.
    @Test fun `a tie check names a candidate that later linked to other work`() {
        val linkedLater = offer(3, linkedJobId = "J5").copy(ambiguousJobIds = listOf("J1"))
        val checks = checks(listOf(linkedLater, passed(4, OfferOutcome.DECLINED, "J1")),
            listOf(drop(10, "J5", listOf("h3"))))
        assertEquals(listOf(ShiftCheck.OfferNotMatched("J1", listOf(3L, 4L))), checks.filterIsInstance<ShiftCheck.OfferNotMatched>())
    }

    @Test fun `a running shift raises no tie check`() {
        assertTrue(checks(listOf(passed(1, OfferOutcome.NOT_TAKEN, "J1"), passed(2, OfferOutcome.NOT_TAKEN, "J1")), emptyList(), ended = false).isEmpty())
    }

    // #1599: one ReceiptNotMatched per open saved receipt, after the offer checks and before the money check.
    private fun deliveryRow(seq: Long, basis: String, pay: Double?) = DeliveryRecord(
        eventSequenceId = seq, sessionId = "S", platform = Platform.DoorDash, jobId = "J$seq", taskId = "t$seq",
        storeName = "Sample Tacos", completedAt = seq * 100, realizedPay = pay, payBasis = basis, netProfit = null,
        realizedMiles = null, realizedMinutes = null, tip = null, basePay = null,
    )

    private fun saved(seq: Long, firstSeenAt: Long, cents: Long) =
        SavedReceipt("receipt:t$seq:$firstSeenAt", "J$seq", "t$seq", firstSeenAt, cents, null, null, proven = false)

    @Test fun `two open receipts raise two checks in first-seen order between the offer and money checks`() { // non-vacuous
        val rows = listOf(deliveryRow(1, PayBasis.OFFER_PAY, 9.0), deliveryRow(2, PayBasis.NONE, null))
        val receipts = ShiftReceipts.of(listOf(saved(2, 300, 2050), saved(1, 200, 2851)), rows, emptyList())
        val money = MoneyCheck(appTotal = 50.0, deliveryPay = 9.0, deliveryPayEstimated = true)
        val out = checks(listOf(passed(5, OfferOutcome.NOT_TAKEN, "J9"), passed(6, OfferOutcome.NOT_TAKEN, "J9")), emptyList(), money = money, receipts = receipts)
        assertEquals(
            listOf(
                ShiftCheck.OfferNotMatched("J9", listOf(5L, 6L)),
                ShiftCheck.ReceiptNotMatched("receipt:t1:200", 2851, 200),
                ShiftCheck.ReceiptNotMatched("receipt:t2:300", 2050, 300),
                ShiftCheck.MoneyDiffers(MoneyCheck.Higher.APP_TOTAL, 41.0),
            ),
            out,
        )
    }

    @Test fun `an open receipt is raised also when the money check agrees`() {
        val receipts = ShiftReceipts.of(listOf(saved(1, 200, 2050)), listOf(deliveryRow(1, PayBasis.OFFER_PAY, 20.5)), emptyList())
        val agrees = MoneyCheck(appTotal = 20.5, deliveryPay = 20.5, deliveryPayEstimated = true)
        assertEquals(listOf(ShiftCheck.ReceiptNotMatched("receipt:t1:200", 2050, 200)), checks(emptyList(), emptyList(), money = agrees, receipts = receipts))
    }

    @Test fun `a matched or settled receipt raises none, nor does a running shift`() {
        val matched = saved(1, 200, 2050)
        val settled = saved(2, 300, 900)
        val rows = listOf(deliveryRow(1, PayBasis.RECEIPT_MATCHED, 20.5).copy(driverAdjustedAt = 9), deliveryRow(2, PayBasis.DROP_SHARE, 9.0))
        val active = listOf(ActiveMatchRow("M1", matched.evidenceId, 1, ReceiptMatchRole.COVERED, 2050, matchedAt = 9))
        assertTrue(checks(emptyList(), emptyList(), receipts = ShiftReceipts.of(listOf(matched, settled), rows, active)).isEmpty())
        val open = ShiftReceipts.of(listOf(saved(3, 400, 500)), listOf(deliveryRow(3, PayBasis.NONE, null)), emptyList())
        assertTrue(checks(emptyList(), emptyList(), ended = false, receipts = open).isEmpty())
    }
}
