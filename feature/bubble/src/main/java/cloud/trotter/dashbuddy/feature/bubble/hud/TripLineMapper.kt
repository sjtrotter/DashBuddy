package cloud.trotter.dashbuddy.feature.bubble.hud

import cloud.trotter.dashbuddy.domain.model.offer.AddOnStops
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.state.Job
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import java.util.Locale

object TripLineMapper {
    fun offer(o: ParsedOffer): TripLineModel = capped(listOf(TripNode(TrackIcon.NAV_PIN)) +
        List(stores(o.orders.map { it.storeName }).size.coerceAtLeast(1)) { TripNode(TrackIcon.STORE) } +
        List(o.orders.size.coerceAtLeast(1)) { TripNode(TrackIcon.HOUSE) })

    fun withAddOn(job: Job, o: ParsedOffer): TripLineModel {
        val pickups = job.tasks.filter { it.phase == TaskPhase.PICKUP }
        val hints = stores(pickups.flatMap { listOfNotNull(it.expectedStoreHint, it.storeName) })
        val addedStores = stores(o.orders.map { it.storeName }).filter { it !in hints }
        val oldStores = pickups.size.coerceAtLeast(1)
        val drops = job.tasks.count { it.phase == TaskPhase.DROPOFF }.coerceAtLeast(1)
        return capped(listOf(TripNode(TrackIcon.NAV_PIN)) +
            List(oldStores) { TripNode(TrackIcon.STORE) } + List(drops) { TripNode(TrackIcon.HOUSE) } +
            List(addedStores.size) { TripNode(TrackIcon.STORE, added = true) } +
            List(AddOnStops.addedDropoffs(o.stopCount, addedStores.size)) { TripNode(TrackIcon.HOUSE, added = true) })
    }

    private fun stores(names: List<String>) = names.map { it.trim().lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }.distinct()
    private fun capped(nodes: List<TripNode>) = TripLineModel(if (nodes.size <= 9) nodes else
        nodes.take(8) + TripNode(TrackIcon.NAV_PIN, nodes.drop(8).any { it.added }, overflow = nodes.size - 8))
}
