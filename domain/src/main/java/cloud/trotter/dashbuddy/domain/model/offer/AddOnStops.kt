package cloud.trotter.dashbuddy.domain.model.offer

import cloud.trotter.dashbuddy.domain.state.Task
import cloud.trotter.dashbuddy.domain.state.TaskPhase
import java.util.Locale

/**
 * One spelling for route-store comparisons, including names resolved after acceptance.
 * lowercase only, no trim, the state machine's historical rule; a trimmed form is a one-line follow-up if a padded name is ever seen in the field
 */
fun storeKey(name: String): String = name.lowercase(Locale.ROOT)

/** Both names belong to the pickup; a resolved name need not spell its accept hint the same way. */
fun ownedStoreKeys(tasks: Collection<Task>): List<String> = tasks
    .filter { it.phase == TaskPhase.PICKUP }
    .flatMap { task -> listOfNotNull(task.expectedStoreHint, task.storeName).map(::storeKey).distinct() }
    .filter { it.isNotEmpty() }

/** First-seen store names new to the route (never one entry per order). */
fun newStores(ownedKeys: Collection<String>, storeNames: Collection<String>): List<String> {
    val owned = ownedKeys.map(::storeKey).toSet()
    return storeNames.filter { it.isNotEmpty() }
        .distinctBy(::storeKey).filter { storeKey(it) !in owned }
}

fun newStores(ownedKeys: Collection<String>, offer: ParsedOffer): List<String> =
    newStores(ownedKeys, offer.orders.map { it.storeName })

/** The route's added stops include both new stores and dropoffs. One add-on always adds a drop. */
object AddOnStops {
    // Untrusted card text outside a real route's range is treated as unparsed.
    const val MAX_ADDON_STOPS = 8

    fun addedDropoffs(stopCount: Int?, newStores: Int): Int =
        stopCount?.takeIf { it in 1..MAX_ADDON_STOPS }
            ?.let { (it - newStores).coerceAtLeast(1) } ?: 1
}
