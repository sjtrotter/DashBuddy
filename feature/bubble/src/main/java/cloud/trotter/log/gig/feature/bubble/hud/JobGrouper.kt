package cloud.trotter.log.gig.feature.bubble.hud

import cloud.trotter.log.gig.domain.model.cards.CardStack
import cloud.trotter.log.gig.domain.model.cards.FlowCardSnapshot
import cloud.trotter.log.gig.domain.model.event.AppEvent
import cloud.trotter.log.gig.domain.model.event.AppEventType
import cloud.trotter.log.gig.domain.model.event.payload.DeliveryPayload
import cloud.trotter.log.gig.domain.model.event.payload.PickupPayload
import cloud.trotter.log.gig.domain.model.event.payload.TaskUnassignedPayload

data class JobGroup(
    val jobId: String, val offers: List<FlowCardSnapshot.Offer>,
    val pickups: List<FlowCardSnapshot.Pickup>,
    val postTasks: List<FlowCardSnapshot.PostTask>, val unassignedAt: Long?, val live: Boolean,
    val startedAt: Long, val endedAt: Long?,
)

/**
 * Accepted offers join jobs only through explicit event or live-job hash links, with no inference.
 * An unlinked accept stays unassigned so a wrong card is never drawn.
 */
object JobGrouper {
    fun group(
        stack: CardStack, events: List<AppEvent>, liveLinks: Map<String, Set<String>> = emptyMap(),
    ): List<JobGroup> {
        // The active snapshot replaces its frozen twin (#458), using the production fold's ids.
        val cards = (stack.completed + listOfNotNull(stack.active)).associateBy { it.id }.values.toList()
        val links = mutableMapOf<String, MutableSet<String>>()
        val abandoned = events.filter { it.type == AppEventType.TASK_UNASSIGNED }
            .mapNotNull { it.payload as? TaskUnassignedPayload }.groupBy { it.jobId }
        events.forEach { event ->
            val pair = when (val p = event.payload) {
                is PickupPayload -> p.jobId to p.jobOfferHashes
                is DeliveryPayload -> p.jobId to p.jobOfferHashes
                is TaskUnassignedPayload -> p.jobId to p.jobOfferHashes
                else -> null
            }
            pair?.let { (id, hashes) ->
                links.getOrPut(id) { mutableSetOf() }.addAll(hashes)
            }
        }
        val byJob = cards.mapNotNull { card -> card.jobId()?.let { it to card } }.groupBy({ it.first }, { it.second })
        val groups = (byJob.keys + abandoned.keys).map { id ->
            val own = byJob[id].orEmpty()
            val unassigned = abandoned[id].orEmpty()
            val live = stack.active?.jobId() == id
            JobGroup(id, emptyList(), own.filterIsInstance<FlowCardSnapshot.Pickup>(),
                own.filterIsInstance<FlowCardSnapshot.PostTask>(),
                unassigned.maxOfOrNull { it.unassignedAt }, live,
                (own.map { it.phaseStartedAt } + unassigned.map { it.startedAt }).min(),
                if (live) null else (own.mapNotNull { it.phaseEndedAt } + unassigned.map { it.unassignedAt }).maxOrNull())
        }.sortedWith(compareBy({ it.startedAt }, { it.jobId }))
        val offers = cards.filterIsInstance<FlowCardSnapshot.Offer>()
            .filter { it.outcome == AppEventType.OFFER_ACCEPTED && it.phaseEndedAt != null }
            .sortedWith(compareBy({ it.phaseEndedAt }, { it.id }))
        val assigned = offers.mapNotNull { offer ->
            val linked = groups.firstOrNull { offer.offerHash in links[it.jobId].orEmpty() }
            val job = linked ?: groups.firstOrNull { offer.offerHash in liveLinks[it.jobId].orEmpty() }
            job?.let { it.jobId to offer }
        }.groupBy({ it.first }, { it.second })
        return groups.map { it.copy(offers = assigned[it.jobId].orEmpty()) }
    }
}

internal fun FlowCardSnapshot.jobId(): String? = when (this) {
    is FlowCardSnapshot.Pickup -> jobId
    is FlowCardSnapshot.Delivery -> jobId
    is FlowCardSnapshot.PostTask -> jobId
    else -> null
}
