package cloud.trotter.log.gig.domain.analytics.checks

import cloud.trotter.log.gig.domain.analytics.SessionDetail
import cloud.trotter.log.gig.domain.analytics.SessionRecord
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.analytics.timeline.PassedRun
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineItem

/** One reason an ended shift needs the dasher's look. */
sealed interface ShiftCheck {
    /** An accepted offer with no delivery, no recorded unassign and no resolution. */
    data class NoDelivery(val offerEventSequenceId: Long) : ShiftCheck

    /**
     * A job with more open accepted offers than deliveries, every offer joined to it. Shown as one group: the app
     * never picks which offer was lost.
     */
    data class MoreAcceptsThanDeliveries(val jobId: String, val accepted: Int, val delivered: Int) : ShiftCheck

    /**
     * #1119 S2: a job whose work tied two or more pooled offers at its store; nothing linked. One per job. The offers
     * include a demoted one (a decline inferred from the confirm sheet) the tie named.
     */
    data class OfferNotMatched(val jobId: String, val offerEventSequenceIds: List<Long>) : ShiftCheck

    /** The shift's independent total and its delivery pay differ ([MoneyCheck]). */
    data class MoneyDiffers(val higher: MoneyCheck.Higher, val amount: Double) : ShiftCheck
}

/**
 * The one owner of "this shift needs a check". Pure, over the shift's folded timeline (which owns how offers join
 * jobs) and its [MoneyCheck]. A running shift raises none: an accepted offer with no delivery yet is just accepted.
 * Read by the shift page; Recap and Home read it too.
 */
object ShiftChecks {

    fun of(detail: SessionDetail, timeline: List<TimelineItem>): List<ShiftCheck> =
        of(detail.session, timeline, MoneyCheck.of(detail))

    fun of(session: SessionRecord, timeline: List<TimelineItem>, money: MoneyCheck?): List<ShiftCheck> {
        if (session.endedAt == null) return emptyList()
        val checks = mutableListOf<ShiftCheck>()
        for (card in timeline.filterIsInstance<JobCard>()) {
            val open = card.openOffers.size
            val delivered = card.deliveries.size
            when {
                delivered == 0 -> card.openOffers.forEach { checks += ShiftCheck.NoDelivery(it.eventSequenceId) }
                card.jobId != null && open > delivered ->
                    checks += ShiftCheck.MoreAcceptsThanDeliveries(card.jobId, open, delivered)
            }
        }
        val tiedByJob = LinkedHashMap<String, MutableList<Long>>()
        for (offer in timeline.filterIsInstance<PassedRun>().flatMap { it.offers }) {
            offer.ambiguousJobIds.forEach { tiedByJob.getOrPut(it) { mutableListOf() } += offer.eventSequenceId }
        }
        tiedByJob.forEach { (jobId, seqs) -> checks += ShiftCheck.OfferNotMatched(jobId, seqs.distinct().sorted()) }
        money?.higher?.let { checks += ShiftCheck.MoneyDiffers(it, money.difference) }
        return checks
    }
}
