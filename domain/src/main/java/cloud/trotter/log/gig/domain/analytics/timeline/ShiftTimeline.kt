package cloud.trotter.log.gig.domain.analytics.timeline

import cloud.trotter.log.gig.domain.analytics.OfferOutcome
import cloud.trotter.log.gig.domain.model.offer.OfferQuoteBasis
import cloud.trotter.log.gig.domain.model.offer.OfferScope
import cloud.trotter.log.gig.domain.model.offer.joinDisplayStores

/**
 * The shift timeline's one owner (#1516): one shift's offers, pickups and deliveries folded into job cards and
 * runs of passed offers, in time order. Pure.
 *
 * - An accepted offer joins a job by hash first (its hash in exactly one job's delivery- or pickup-row hash set),
 *   then by `linkedJobId`. Nothing is inferred from time or store; an offer that joins nothing is a card of its own.
 * - A card with an offer is keyed by its first offer, so the key holds as the job gains a pickup and deliveries.
 * - Job cards sort by their first accepted offer, a card with no offer by its first delivery, and passed offers
 *   by their own decision; ties fall to the event sequence id, which is unique across the log.
 * - Consecutive declined and expired offers between two cards, before the first or after the last, are one
 *   [PassedRun]. An expired offer is counted as expired, never as declined.
 */
object ShiftTimeline {

    private val offerOrder = compareBy<TimelineOffer>({ it.decidedAt }, { it.eventSequenceId })
    private val deliveryOrder = compareBy<TimelineDelivery>({ it.completedAt }, { it.eventSequenceId })

    fun fold(
        offers: List<TimelineOffer>,
        pickups: List<TimelinePickup>,
        deliveries: List<TimelineDelivery>,
        running: Boolean,
    ): List<TimelineItem> {
        val sortedOffers = offers.filter { it.outcome != null }.sortedWith(offerOrder)
        val deliveriesByJob = deliveries.sortedWith(deliveryOrder).groupBy { it.jobId }
        val pickupsByJob = pickups.sortedBy { it.eventSequenceId }.groupBy { it.jobId }
        val jobsByHash = (deliveries.flatMap { d -> d.offerHashes.map { it to d.jobId } } +
            pickups.flatMap { p -> p.jobOfferHashes.orEmpty().map { it to p.jobId } })
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

        val offersByJob = LinkedHashMap<String, MutableList<TimelineOffer>>()
        val unjoined = mutableListOf<TimelineOffer>()
        for (offer in sortedOffers.filter { it.outcome == OfferOutcome.ACCEPTED }) {
            val jobId = joinedJob(offer, jobsByHash)
            if (jobId != null) offersByJob.getOrPut(jobId) { mutableListOf() }.add(offer) else unjoined.add(offer)
        }

        val placed = mutableListOf<Placed>()
        for (jobId in (offersByJob.keys + deliveriesByJob.keys)) {
            val first = offersByJob[jobId]?.first()
            val card = card(
                key = first?.let { "offer:${it.eventSequenceId}" } ?: "job:$jobId", jobId = jobId,
                offers = offersByJob[jobId].orEmpty(),
                drops = deliveriesByJob[jobId].orEmpty(),
                pickups = pickupsByJob[jobId].orEmpty(),
                running = running,
            )
            placed += Placed(card, null, sortKeyOf(offersByJob[jobId].orEmpty(), deliveriesByJob[jobId].orEmpty()))
        }
        for (offer in unjoined) {
            val card = card("offer:${offer.eventSequenceId}", null, listOf(offer), emptyList(), emptyList(), running)
            placed += Placed(card, null, offer.decidedAt to offer.eventSequenceId)
        }
        for (offer in sortedOffers) {
            val outcome = offer.outcome
            if (outcome == OfferOutcome.DECLINED || outcome == OfferOutcome.TIMED_OUT) {
                placed += Placed(null, passed(offer, outcome), offer.decidedAt to offer.eventSequenceId)
            }
        }
        placed.sortWith(compareBy({ it.sortKey.first }, { it.sortKey.second }))
        return coalesceRuns(placed)
    }

    /** By hash when exactly one job names the offer; else its `linkedJobId` when the hash join does not contradict it. */
    private fun joinedJob(offer: TimelineOffer, jobsByHash: Map<String, Set<String>>): String? {
        val byHash = jobsByHash[offer.offerHash].orEmpty()
        if (byHash.size == 1) return byHash.single()
        val linked = offer.linkedJobId ?: return null
        return linked.takeIf { byHash.isEmpty() || it in byHash }
    }

    /** A job always has an offer or a delivery: it is keyed from one of them. */
    private fun sortKeyOf(offers: List<TimelineOffer>, drops: List<TimelineDelivery>): Pair<Long, Long> =
        offers.firstOrNull()?.let { it.decidedAt to it.eventSequenceId }
            ?: drops.first().let { it.completedAt to it.eventSequenceId }

    private fun card(
        key: String,
        jobId: String?,
        offers: List<TimelineOffer>,
        drops: List<TimelineDelivery>,
        pickups: List<TimelinePickup>,
        running: Boolean,
    ): JobCard {
        val base = offers.firstOrNull()?.takeUnless(::isAddOnQuote)
        val offerLine = base?.let { OfferLine(it.payAmount, it.distanceMiles) }
        val addOns = offers.filter { it !== base }.map(::addOnLine)
        val lines = drops.map { d ->
            val source = PaySource.of(d.payBasis, d.realizedPay, drops.size)
            DeliveryLine(d.eventSequenceId, d.completedAt, d.storeName, d.realizedPay, source, source.estimated, d.cashTip)
        }
        val pays = drops.mapNotNull { it.realizedPay }
        val cash = drops.mapNotNull { it.cashTip }
        val state = when {
            drops.isNotEmpty() -> JobState.NORMAL
            running -> JobState.ACCEPTED_NO_DELIVERY_YET
            else -> JobState.NO_DELIVERY
        }
        return JobCard(
            key = key,
            jobId = jobId,
            stores = storesOf(offers, drops, pickups),
            firstOfferAt = offers.firstOrNull()?.decidedAt,
            paidTotal = pays.takeIf { it.isNotEmpty() }?.sum(),
            paidTotalEstimated = lines.any { it.estimated && it.pay != null },
            offerLine = offerLine,
            addOns = addOns,
            deliveries = lines,
            cashTips = cash.takeIf { it.isNotEmpty() }?.sum(),
            state = state,
            offerEventSequenceIds = offers.map { it.eventSequenceId },
            milestones = milestones(offers, base, offerLine, addOns, pickups, drops, lines),
        )
    }

    private fun isAddOnQuote(offer: TimelineOffer): Boolean =
        offer.offerScope == OfferScope.ADD_ON || offer.quoteBasis == OfferQuoteBasis.INCREMENTAL

    private fun addOnLine(offer: TimelineOffer): AddOnLine = when {
        offer.quoteBasis == OfferQuoteBasis.INCREMENTAL ->
            AddOnLine.Incremental(offer.eventSequenceId, offer.payAmount, offer.distanceMiles)
        offer.offerScope == OfferScope.ADD_ON ->
            AddOnLine.NewTotal(offer.eventSequenceId, offer.payAmount, offer.distanceMiles)
        else -> AddOnLine.Offered(offer.eventSequenceId, offer.payAmount, offer.distanceMiles)
    }

    private fun storesOf(offers: List<TimelineOffer>, drops: List<TimelineDelivery>, pickups: List<TimelinePickup>): String? =
        offers.firstNotNullOfOrNull { it.merchantName?.takeIf(String::isNotBlank) }
            ?: drops.mapNotNull { it.storeName?.takeIf(String::isNotBlank) }.distinct().takeIf { it.isNotEmpty() }?.joinDisplayStores()
            ?: pickups.map { it.storeName }.filter(String::isNotBlank).distinct().takeIf { it.isNotEmpty() }?.joinDisplayStores()

    private fun milestones(
        offers: List<TimelineOffer>,
        base: TimelineOffer?,
        offerLine: OfferLine?,
        addOns: List<AddOnLine>,
        pickups: List<TimelinePickup>,
        drops: List<TimelineDelivery>,
        lines: List<DeliveryLine>,
    ): List<Milestone> {
        val addOnBySeq = addOns.associateBy { it.eventSequenceId }
        val out = mutableListOf<Milestone>()
        for (offer in offers) {
            out += if (offer === base) {
                Milestone(MilestoneKind.ACCEPTED_OFFERED, offer.decidedAt, offer = offerLine)
            } else {
                Milestone(MilestoneKind.ACCEPTED_OFFERED, offer.decidedAt, addOn = addOnBySeq[offer.eventSequenceId])
            }
        }
        for (p in pickups) {
            p.arrivedAt?.let { out += Milestone(MilestoneKind.AT_STORE, it, store = p.storeName) }
            p.confirmedAt?.let { out += Milestone(MilestoneKind.PICKED_UP, it, store = p.storeName) }
        }
        for ((d, line) in drops.zip(lines)) {
            d.arrivedAt?.let { out += Milestone(MilestoneKind.AT_CUSTOMER, it, delivery = line) }
            out += Milestone(MilestoneKind.DELIVERED, line.at, delivery = line)
        }
        return out.sortedWith(compareBy({ it.at }, { it.kind.ordinal }))
    }

    private fun passed(offer: TimelineOffer, outcome: OfferOutcome) = PassedOffer(
        eventSequenceId = offer.eventSequenceId,
        at = offer.decidedAt,
        stores = offer.merchantName,
        pay = offer.payAmount,
        miles = offer.distanceMiles,
        outcome = outcome,
    )

    /** Placed rows, with every maximal stretch of [PassedOffer]s folded into one [PassedRun]. */
    private fun coalesceRuns(entries: List<Placed>): List<TimelineItem> {
        val out = mutableListOf<TimelineItem>()
        val run = mutableListOf<PassedOffer>()
        fun flush() {
            if (run.isEmpty()) return
            out += PassedRun(
                key = "run:${run.first().eventSequenceId}",
                declined = run.count { it.outcome == OfferOutcome.DECLINED },
                expired = run.count { it.outcome == OfferOutcome.TIMED_OUT },
                from = run.first().at,
                to = run.last().at,
                offers = run.toList(),
            )
            run.clear()
        }
        for (entry in entries) {
            val card = entry.card
            if (card == null) {
                run += entry.passed!!
            } else {
                flush()
                out += card
            }
        }
        flush()
        return out
    }

    /** One placed row before runs are folded: a card, or one passed offer. */
    private class Placed(val card: JobCard?, val passed: PassedOffer?, val sortKey: Pair<Long, Long>)
}
