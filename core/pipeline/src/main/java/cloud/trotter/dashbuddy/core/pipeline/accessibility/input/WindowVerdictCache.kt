package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

/**
 * #1152 D3, widened by PR #1155 review BB7 — per window id: its owning package (when a root fetch
 * resolved one) and its memoized overlay-candidacy [Verdict]. A window's root is fetched (a binder
 * round-trip) at most once per window id to learn who owns it, and a system window's size + package
 * verdict is decided once per window id, not per frame — so the status bar, the nav bar and a
 * platform's puck are probed ONCE, and `overlayRejected{…}` counts decisions, not frames.
 *
 * Only DECIDED verdicts are memoized: an unreadable root is retried next frame, and an unknown
 * display area is never memoized (it can become known). Bounds are treated as stable per window id
 * — a window that resizes produces a `TYPE_WINDOWS_CHANGED`, which CLEARS this cache
 * ([AccessibilitySource.emit]); window ids are allocated monotonically, so an entry written by a
 * collector that read the old list only describes a window that no longer exists.
 *
 * Bounded LRU ([CAPACITY] entries). Only package names and verdict enums — never a node, never
 * third-party text; every snapshot still maps a freshly-fetched root and re-verifies its package on
 * it. Thread-safe (the accessibility thread clears it; the pipeline collectors read and fill it).
 */
internal class WindowVerdictCache(private val capacity: Int = CAPACITY) {

    /** A decided overlay-candidacy verdict for a `TYPE_SYSTEM` window (#1152 D2). */
    enum class Verdict { CANDIDATE, TOO_SMALL, NOT_OVERLAY_PLATFORM }

    data class Entry(val packageName: String?, val verdict: Verdict?)

    private val map = object : LinkedHashMap<Int, Entry>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?): Boolean = size > capacity
    }

    @Synchronized
    fun get(windowId: Int): Entry? = map[windowId]

    /** The window's package, keeping any verdict already recorded. */
    @Synchronized
    fun putPackage(windowId: Int, packageName: String) {
        map[windowId] = Entry(packageName, map[windowId]?.verdict)
    }

    /** A decided verdict; [packageName] null when it was decided without a root fetch (size). */
    @Synchronized
    fun putVerdict(windowId: Int, packageName: String?, verdict: Verdict) {
        map[windowId] = Entry(packageName ?: map[windowId]?.packageName, verdict)
    }

    @Synchronized
    fun clear() = map.clear()

    @get:Synchronized
    val size: Int get() = map.size

    companion object {
        const val CAPACITY = 64
    }
}
