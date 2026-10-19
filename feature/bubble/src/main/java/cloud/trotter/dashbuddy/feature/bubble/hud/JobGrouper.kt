package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.cards.CardStack
import cloud.trotter.dashbuddy.domain.model.cards.FlowCardSnapshot
import cloud.trotter.dashbuddy.domain.model.event.AppEvent
import cloud.trotter.dashbuddy.domain.model.event.AppEventType
import cloud.trotter.dashbuddy.domain.model.event.payload.DeliveryPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.OfferPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.PickupPayload
import cloud.trotter.dashbuddy.domain.model.event.payload.TaskUnassignedPayload
import cloud.trotter.dashbuddy.domain.model.offer.OfferQuoteBasis

data class JobGroup(
    val jobId: String, val offers: List<FlowCardSnapshot.Offer>,
    val pickups: List<FlowCardSnapshot.Pickup>,
    val postTasks: List<FlowCardSnapshot.PostTask>, val unassignedAt: Long?, val live: Boolean,
    val startedAt: Long, val endedAt: Long?,
)

object JobGrouper {
    /**
     * Accept and first-card events from one step can carry slightly different timestamps.
     * Allow that skew while excluding jobs already open well before a standalone accept.
     */
    private const val ACCEPT_JOIN_SKEW_MS = 2_000L

    fun group(stack: CardStack, events: List<AppEvent>): List<JobGroup> {
        // The active snapshot replaces its frozen twin (#458), using the production fold's ids.
        val cards = (stack.completed + listOfNotNull(stack.active)).associateBy { it.id }.values.toList()
        val links = mutableMapOf<String, MutableSet<String>>()
        val offerSession = mutableMapOf<String, String>()
        val jobSession = mutableMapOf<String, String>()
        val abandoned = events.filter { it.type == AppEventType.TASK_UNASSIGNED }
            .mapNotNull { it.payload as? TaskUnassignedPayload }.groupBy { it.jobId }
        events.forEach { event ->
            if (event.type == AppEventType.OFFER_ACCEPTED) {
                val offer = event.payload as? OfferPayload
                if (offer != null) event.sessionId?.let { offerSession[offer.offerHash] = it }
            }
            val pair = when (val p = event.payload) {
                is PickupPayload -> p.jobId to p.jobOfferHashes
                is DeliveryPayload -> p.jobId to p.jobOfferHashes
                is TaskUnassignedPayload -> p.jobId to p.jobOfferHashes
                else -> null
            }
            pair?.let { (id, hashes) ->
                links.getOrPut(id) { mutableSetOf() }.addAll(hashes)
                event.sessionId?.let { jobSession[id] = it }
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
            val at = offer.phaseEndedAt!!
            val linked = groups.firstOrNull { offer.offerHash in links[it.jobId].orEmpty() }
            val job = linked ?: run {
                val session = offerSession[offer.offerHash]
                val candidates = groups.filter { jobSession[it.jobId] == null || jobSession[it.jobId] == session }
                when (offer.quoteBasis) {
                    OfferQuoteBasis.INCREMENTAL -> candidates.filter {
                        it.startedAt <= at && (it.endedAt == null || at <= it.endedAt)
                    }.singleOrNull()
                    // A standalone accept starts a new job, even while the previous receipt is open.
                    OfferQuoteBasis.TOTAL -> candidates.firstOrNull { it.startedAt >= at - ACCEPT_JOIN_SKEW_MS }
                }
            }
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
