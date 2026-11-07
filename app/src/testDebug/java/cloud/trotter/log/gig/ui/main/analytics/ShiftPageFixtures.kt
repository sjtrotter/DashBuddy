package cloud.trotter.log.gig.ui.main.analytics

import cloud.trotter.log.gig.domain.analytics.DeliveryRecord
import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.analytics.PayBasis
import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.timeline.ShiftTimeline
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineDelivery
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineItem
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineOffer
import cloud.trotter.log.gig.domain.analytics.timeline.TimelinePickup
import cloud.trotter.log.gig.domain.model.event.payload.SessionEndSource
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.state.Platform
import java.time.Instant

/**
 * Shift pages for the Compose tests and the screenshots, folded through the real [ShiftTimeline]. Times are UTC
 * on Tuesday 2026-10-27, the day before the screenshots' clock. Store names are sample merchants.
 */
internal object ShiftPageFixtures {
    private val start = Instant.parse("2026-10-27T16:58:00Z").toEpochMilli()
    private fun at(minutes: Int) = start + minutes * 60_000L

    /** Sequence ids of the stack's two delivery rows, so a test can name the delivery a Fix opens. */
    const val STACK_FIRST_DROP = 101L
    const val STACK_SECOND_DROP = 102L
    const val STACK_KEY = "offer:13"

    private fun offer(seq: Long, minutes: Int, outcome: OfferOutcome, store: String, pay: Double, miles: Double,
                      scope: OfferScope? = OfferScope.STANDALONE, basis: OfferQuoteBasis? = OfferQuoteBasis.TOTAL) =
        TimelineOffer(seq, "h$seq", outcome, at(minutes), pay, miles, store, scope, basis, linkedJobId = null)

    private fun record(seq: Long, job: String, store: String, minutes: Int, pay: Double?, basis: String,
                       net: Double?, cash: Double? = null) = DeliveryRecord(
        eventSequenceId = seq, sessionId = "shift-1", platform = Platform.DoorDash, jobId = job, taskId = "t$seq",
        storeName = store, completedAt = at(minutes), realizedPay = pay, payBasis = basis, netProfit = net,
        realizedMiles = 3.0, realizedMinutes = 12.0, tip = null, basePay = null, cashTip = cash,
    )

    private fun session(endedMinutes: Int?, reported: Double?, endSource: String?, miles: Double, deliveries: Int) =
        SessionRecord(
            sessionId = "shift-1", platform = Platform.DoorDash, startedAt = start, endedAt = endedMinutes?.let(::at),
            reportedEarnings = reported, endSource = endSource, reportedDurationMillis = null, miles = miles,
            deliveries = deliveries, jobsCompleted = 2, offersReceived = 9, offersAccepted = 3, offersDeclined = 5,
            offersTimeout = 1,
        )

    /** A stack (Five Guys and Chipotle, two drops, cash on the second), an add-on job, and runs of passed offers. */
    fun stackAndAddOn(running: Boolean = false): SessionDetailUiState {
        val offers = listOf(
            offer(10, 7, OfferOutcome.DECLINED, "Chick-fil-A", 6.50, 4.1),
            offer(11, 14, OfferOutcome.DECLINED, "Panda Express", 4.00, 6.0),
            offer(12, 22, OfferOutcome.TIMED_OUT, "Wingstop", 3.50, 5.2),
            offer(13, 32, OfferOutcome.ACCEPTED, "Five Guys & Chipotle", 9.75, 3.1),
            offer(14, 97, OfferOutcome.ACCEPTED, "Sample Burgers", 6.25, 2.4),
            offer(20, 104, OfferOutcome.ACCEPTED, "Sample Tacos", 4.00, 1.8, OfferScope.ADD_ON, OfferQuoteBasis.INCREMENTAL),
            offer(30, 150, OfferOutcome.DECLINED, "Sample Pizza", 5.00, 7.3),
        )
        val records = listOf(
            record(STACK_FIRST_DROP, "job-1", "Five Guys", 72, 6.25, PayBasis.DROP_SHARE, 3.10),
            record(STACK_SECOND_DROP, "job-1", "Chipotle", 84, 5.50, PayBasis.DROP_SHARE, 2.40, cash = 5.0),
            record(103, "job-2", "Sample Burgers", 128, 7.00, PayBasis.DROP_SHARE, 3.80),
            record(104, "job-2", "Sample Tacos", 136, 4.00, PayBasis.OFFER_PAY, 1.90),
        )
        val pickups = listOf(
            TimelinePickup(201, "job-1", "Five Guys", at(42), at(48), listOf("h13")),
            TimelinePickup(202, "job-1", "Chipotle", at(52), at(57), listOf("h13")),
        )
        val deliveries = records.map { r ->
            TimelineDelivery(
                r.eventSequenceId, r.jobId, r.storeName, arrivedAt = r.completedAt - 120_000L, completedAt = r.completedAt,
                realizedPay = r.realizedPay, payBasis = r.payBasis, cashTip = r.cashTip,
                jobOfferHashes = if (r.jobId == "job-1") listOf("h13") else listOf("h14", "h20"), soleOfferHash = null,
            )
        }
        val detail = SessionDetail(
            session(if (running) null else 366, 25.50, SessionEndSource.SUMMARY_SCREEN, 41.0, records.size), records,
        )
        return state(detail, ShiftTimeline.fold(offers, pickups, deliveries, running))
    }

    /**
     * The heaviest field shape: 47 offers and no deliveries. Two accepted offers that never delivered split the
     * passed ones into three runs of declined and expired offers.
     */
    fun heavyShift(): SessionDetailUiState {
        val offers = (0 until 47).map { i ->
            val outcome = when {
                i == 15 || i == 31 -> OfferOutcome.ACCEPTED
                i % 4 == 3 -> OfferOutcome.TIMED_OUT
                else -> OfferOutcome.DECLINED
            }
            offer(1_000L + i, 3 + i * 7, outcome, "Sample Store ${i % 6 + 1}", 3.0 + (i % 5), 2.0 + (i % 7))
        }
        val detail = SessionDetail(session(366, null, SessionEndSource.SUMMARY_SCREEN, 58.0, 0), emptyList())
        return state(detail, ShiftTimeline.fold(offers, emptyList(), emptyList(), running = false))
    }

    private fun state(detail: SessionDetail, timeline: List<TimelineItem>) =
        SessionDetailUiState(detail = detail, timeline = timeline, loading = false)
}
