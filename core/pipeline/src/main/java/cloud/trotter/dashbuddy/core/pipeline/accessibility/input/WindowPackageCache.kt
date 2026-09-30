package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

/**
 * #1152 D3 — `windowId → packageName`, so a window's root is fetched (a binder round-trip) at most
 * once per window id to learn who owns it. Filled on every root fetch that resolves a package
 * (application AND system windows); read by the overlay-candidate package check and by
 * [AccessibilitySource.foregroundWindow] before it fetches a root.
 *
 * Bounded LRU ([CAPACITY] entries — a screen holds a handful of windows; the bound only matters
 * across a long topology-quiet stretch) and CLEARED on every `TYPE_WINDOWS_CHANGED`
 * ([AccessibilitySource.emit]), because the window list changed. Window ids are allocated
 * monotonically by the framework, so an entry written just after a clear (a collector that read
 * the old list) can only describe a window that no longer exists — it is never looked up again.
 *
 * Only the PACKAGE is cached, never a node: every snapshot still maps a freshly-fetched root, and
 * the package that gates a mapped snapshot is re-read from that root. Thread-safe (the
 * accessibility thread clears it; the pipeline collectors read and fill it). Holds package names
 * only — no third-party text.
 */
internal class WindowPackageCache(private val capacity: Int = CAPACITY) {

    private val map = object : LinkedHashMap<Int, String>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean = size > capacity
    }

    @Synchronized
    fun get(windowId: Int): String? = map[windowId]

    @Synchronized
    fun put(windowId: Int, packageName: String) {
        map[windowId] = packageName
    }

    @Synchronized
    fun clear() = map.clear()

    @get:Synchronized
    val size: Int get() = map.size

    companion object {
        const val CAPACITY = 64
    }
}
