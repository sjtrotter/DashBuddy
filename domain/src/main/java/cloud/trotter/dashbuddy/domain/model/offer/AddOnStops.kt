package cloud.trotter.dashbuddy.domain.model.offer

/** The route's added stops include both new stores and dropoffs. One add-on always adds a drop. */
object AddOnStops {
    // Untrusted card text outside a real route's range is treated as unparsed.
    const val MAX_ADDON_STOPS = 8

    fun addedDropoffs(stopCount: Int?, newStores: Int): Int =
        stopCount?.takeIf { it in 1..MAX_ADDON_STOPS }
            ?.let { (it - newStores).coerceAtLeast(1) } ?: 1
}
