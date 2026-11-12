package cloud.trotter.log.gig.domain.model.offer

import cloud.trotter.log.gig.domain.state.StoreKeys
import cloud.trotter.log.gig.domain.state.Task
import cloud.trotter.log.gig.domain.state.TaskPhase

/**
 * The one store key for route comparisons, including names resolved after acceptance: the chain key
 * ([StoreKeys.normalizedChain]), so names that differ only by a location qualifier are one store (#1119 S2). The offer
 * pool matches work to offers with the same key. Not idempotent (its single trailing ` - X` strip runs again on a
 * second pass), so a key is never keyed twice.
 */
fun storeKey(name: String): String = StoreKeys.normalizedChain(name)

/** Both names belong to the pickup; a resolved name need not spell its accept hint the same way. Keyed once. */
fun ownedStoreKeys(tasks: Collection<Task>): List<String> = tasks
    .filter { it.phase == TaskPhase.PICKUP }
    .flatMap { task -> listOfNotNull(task.expectedStoreHint, task.storeName).map(::storeKey).distinct() }
    .filter { it.isNotEmpty() }

/** First-seen store names new to the route (never one entry per order). Owned keys come from [ownedStoreKeys]. */
fun newStores(ownedKeys: Collection<String>, storeNames: Collection<String>): List<String> =
    AddOnStops.newStores(ownedKeys, storeNames)

fun newStores(ownedKeys: Collection<String>, offer: ParsedOffer): List<String> =
    AddOnStops.newStores(ownedKeys, offer)

/** The route's added stops include both new stores and dropoffs. One add-on always adds a drop. */
object AddOnStops {
    // Untrusted card text outside a real route's range is treated as unparsed.
    const val MAX_ADDON_STOPS = 8

    /**
     * First-seen names after the caller has prepared incoming hints. Owned keys come from [ownedStoreKeys] and are
     * already keys: only [storeNames] is keyed here.
     */
    fun newStores(ownedKeys: Collection<String>, storeNames: Collection<String>): List<String> {
        val owned = ownedKeys.toSet()
        return storeNames.filter { it.isNotEmpty() }
            .distinctBy(::storeKey).filter { storeKey(it) !in owned }
    }

    /** Mirror appendAddOn's incoming hint trimming. Owned keys come from [ownedStoreKeys]. */
    fun newStores(ownedKeys: Collection<String>, offer: ParsedOffer): List<String> =
        newStores(ownedKeys, offer.orders.map { it.storeName.trim() })

    fun addedDropoffs(stopCount: Int?, newStores: Int): Int =
        stopCount?.takeIf { it in 1..MAX_ADDON_STOPS }
            ?.let { (it - newStores).coerceAtLeast(1) } ?: 1
}
