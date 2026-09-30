package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

/**
 * #1152 D3, widened by PR #1155 review BB7 — per window id: its owning package (when a root fetch
 * resolved one) and its memoized overlay-candidacy [Verdict]. A window's root is fetched (a binder
 * round-trip) at most once per window id to learn who owns it, and a system window's size + package
 * verdict is decided once per window id, not per frame — so the status bar, the nav bar and a
 * platform's puck cost no root fetch after the first, and `overlayRejected{…}` counts decisions,
 * not frames.
 *
 * Only DECIDED verdicts are memoized: an unreadable root is retried next frame, and an unknown
 * display area is never memoized (it can become known).
 *
 * PR #1155 review CC1 — the memo is GEOMETRY- and GENERATION-checked:
 * - a verdict stores the [Bounds] it was decided on; a caller honours it only while the window's
 *   CURRENT bounds are equal (a resized window — the puck growing, an offer shrinking — is
 *   re-probed);
 * - [clear] (every `TYPE_WINDOWS_CHANGED`, [AccessibilitySource.emit]) bumps a topology
 *   [generation]; a caller reads the generation BEFORE it starts probing and passes it to every
 *   write, and a write whose generation moved is DISCARDED — a collector paused in a root fetch
 *   across a topology change can never publish a verdict decided on the old topology.
 *
 * Bounded LRU ([CAPACITY] entries). Only package names, verdict enums and four ints — never a node,
 * never third-party text; every snapshot still maps a freshly-fetched root and re-verifies its
 * package on it. Thread-safe (the accessibility thread clears it; the pipeline collectors read and
 * fill it).
 */
internal class WindowVerdictCache(private val capacity: Int = CAPACITY) {

    /** A decided overlay-candidacy verdict for a `TYPE_SYSTEM` window (#1152 D2). */
    enum class Verdict { CANDIDATE, TOO_SMALL, NOT_OVERLAY_PLATFORM }

    /** Immutable screen bounds a verdict was decided on (CC1). */
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Entry(val packageName: String?, val verdict: Verdict?, val bounds: Bounds? = null)

    private val map = object : LinkedHashMap<Int, Entry>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Entry>?): Boolean = size > capacity
    }

    private var gen = 0L

    /** The topology generation — read BEFORE probing, passed to every write (CC1). */
    @get:Synchronized
    val generation: Long get() = gen

    @Synchronized
    fun get(windowId: Int): Entry? = map[windowId]

    /** The window's package, keeping any verdict already recorded. Discarded if [generation] moved. */
    @Synchronized
    fun putPackage(windowId: Int, packageName: String, generation: Long) {
        if (generation != gen) return
        val prior = map[windowId]
        map[windowId] = Entry(packageName, prior?.verdict, prior?.bounds)
    }

    /**
     * A decided verdict on [bounds]; [packageName] null when it was decided without a root fetch
     * (size). Discarded if [generation] moved since the caller started probing.
     */
    @Synchronized
    fun putVerdict(windowId: Int, packageName: String?, verdict: Verdict, bounds: Bounds, generation: Long) {
        if (generation != gen) return
        map[windowId] = Entry(packageName ?: map[windowId]?.packageName, verdict, bounds)
    }

    /** Topology changed: forget everything and bump the generation (in-flight writes are discarded). */
    @Synchronized
    fun clear() {
        map.clear()
        gen++
    }

    @get:Synchronized
    val size: Int get() = map.size

    companion object {
        const val CAPACITY = 64
    }
}
