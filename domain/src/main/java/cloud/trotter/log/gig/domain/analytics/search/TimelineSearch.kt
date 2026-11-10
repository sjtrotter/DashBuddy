package cloud.trotter.log.gig.domain.analytics.search

import cloud.trotter.log.gig.domain.analytics.recap.DayShift
import cloud.trotter.log.gig.domain.analytics.timeline.JobCard
import cloud.trotter.log.gig.domain.analytics.timeline.PassedOffer
import cloud.trotter.log.gig.domain.analytics.timeline.PassedRun
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineDelivery
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineItem
import cloud.trotter.log.gig.domain.analytics.timeline.TimelineOffer

/** One row a store search shows, in timeline order. [key] is the shift page's own key for the row. */
sealed interface SearchRow {
    val key: String

    /** A whole job card: a match on any of its offers or deliveries never shows a fragment. */
    data class Job(val card: JobCard) : SearchRow {
        override val key: String get() = card.key
    }

    /** One declined or expired offer, out of its run; keyed `offer:<seq>` like its thin row on the shift page. */
    data class Passed(val offer: PassedOffer) : SearchRow {
        override val key: String get() = "offer:${offer.eventSequenceId}"
    }
}

/** One shift's matching rows, under its own header (its date, app and times). Two apps never share one. */
data class ShiftMatches(val shift: DayShift, val rows: List<SearchRow>)

/**
 * The store search over one shift's folded timeline (#1540). Pure. An offer matches on its `merchantName`, a delivery
 * on its `storeName` (both as recorded, "A & B" for a stack); resolved offers count. A [JobCard] is selected whole
 * when any of its accepted offers (add-ons included) or deliveries matched; from a [PassedRun] only the matching
 * [PassedOffer]s come out, each alone. Order is the timeline's.
 */
object TimelineSearch {
    fun filter(
        items: List<TimelineItem>,
        offers: List<TimelineOffer>,
        deliveries: List<TimelineDelivery>,
        query: StoreQuery?,
    ): List<SearchRow> {
        if (query == null) return emptyList()
        val offerHits = offers.filter { query.matches(it.merchantName) }.mapTo(HashSet()) { it.eventSequenceId }
        val dropHits = deliveries.filter { query.matches(it.storeName) }.mapTo(HashSet()) { it.eventSequenceId }
        if (offerHits.isEmpty() && dropHits.isEmpty()) return emptyList()
        return items.flatMap { item ->
            when (item) {
                is JobCard -> {
                    val hit = item.offerEventSequenceIds.any { it in offerHits } ||
                        item.deliveries.any { it.deliveryEventSequenceId in dropHits }
                    if (hit) listOf(SearchRow.Job(item)) else emptyList()
                }
                is PassedRun -> item.offers.filter { it.eventSequenceId in offerHits }.map { SearchRow.Passed(it) }
            }
        }
    }
}
