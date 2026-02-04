package cloud.trotter.dashbuddy.data.log

import java.util.LinkedList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the last N screens visited.
 * Used to add context to snapshots (e.g. "How did we get to this Unknown screen?")
 */
@Singleton
class Breadcrumbs @Inject constructor() {
    private val history = LinkedList<String>()
    private val limit = 10

    @Synchronized
    fun add(screenName: String) {
        // Deduplicate sequential: [MAP, MAP, MAP] -> [MAP]
        if (history.peekLast() == screenName) return

        if (history.size >= limit) {
            history.removeFirst()
        }
        history.addLast(screenName)
    }

    @Synchronized
    fun getTrail(): List<String> {
        return history.toList()
    }
}