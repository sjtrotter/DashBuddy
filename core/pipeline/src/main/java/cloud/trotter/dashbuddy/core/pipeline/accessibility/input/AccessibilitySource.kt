package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.OverlayRejectReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toUiNode
import cloud.trotter.dashbuddy.domain.state.Platform
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilitySource @Inject constructor(
    /** Counts the #1152 overlay-candidate decisions (`overlayRejected{…}`). */
    private val stats: PipelineStats,
) {

    /**
     * Stats-less construction for the unit tests that only exercise window/root plumbing. A
     * secondary constructor, not a default argument — the PipelineStats (PR #1066) doctrine: an
     * all-default primary would synthesize a second `@Inject`-annotated constructor Dagger rejects.
     */
    constructor() : this(PipelineStats())

    /** #1152 D3/BB7: `windowId → (package, overlay verdict)`, cleared on every topology change ([emit]). */
    private val packageCache = WindowVerdictCache()

    /**
     * PR #1155 review FF9/HH6: the front-window walk — decision logic this I/O seam delegates to.
     * Internal consumers (the topology pipeline, the event resolver, tests) reach it DIRECTLY; the
     * only public entry kept here is [foregroundWindow].
     */
    internal val walk = FrontWindowWalk(
        ownPackage = { ownPackage() },
        cache = packageCache,
        stats = stats,
        displayMetrics = { serviceRef?.get()?.resources?.displayMetrics },
    )

    // --- 1. The Event Stream (Push) ---
    // #1148 D1: the flow carries the immutable [AccEvent] envelope, never the framework-owned
    // AccessibilityEvent — the buffer is read after the callback returned, when the framework
    // may already have reused the raw event.
    private val _events = MutableSharedFlow<AccEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<AccEvent> = _events.asSharedFlow()

    /** Copies [event]'s scalars into an [AccEvent] (the one construction site) and emits it. */
    fun emit(event: AccessibilityEvent) {
        // #1152 D3: the window list changed — cached window ids may be stale (cleared BEFORE the
        // event reaches any collector, so no collector reads the old topology's cache after it).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) packageCache.clear()
        _events.tryEmit(AccEvent.from(event))
    }

    /**
     * Exposes the raw, live native root for surgical strikes (clicks).
     * The caller is responsible for calling recycle() on the returned node!
     */
    fun getLiveNativeRoot(): AccessibilityNodeInfo? {
        val service = serviceRef?.get() ?: return null
        return service.rootInActiveWindow
    }

    /**
     * All live window roots (native), **active window first, deduped**. Needed for clicks: the
     * node to tap (e.g. DoorDash's Accept/Decline button) may be in a window other than the active
     * one — when the user taps the bubble, the *bubble* is the active window, so a search limited to
     * [getLiveNativeRoot] misses the underlying app's nodes. Requires
     * `flagRetrieveInteractiveWindows` (set in the service config).
     *
     * `rootInActiveWindow` is also enumerated inside `service.windows` (the active window's root
     * added a second time), so without deduping the active window's node would appear **twice** —
     * the correct click target then ties with ITSELF and the fail-closed disambiguator aborts the
     * tap (#788). We dedup with `==` (i.e. `AccessibilityNodeInfo.equals`, which compares
     * `windowId` + `sourceNodeId` — the two fetches of the same active-window root are equal), NOT
     * a hash-based `distinct()`: `AccessibilityNodeInfo` does not guarantee a `hashCode` consistent
     * with that `equals`. Active-window-FIRST ordering is preserved for stability/diagnostics —
     * `rootInActiveWindow` is added first and kept, its later twin dropped. (Nothing consumes list
     * position anymore: `UiInteractionHandler`'s #788 scoping identifies the active window by `==`
     * against [LiveRoots.active], not by index.) The list is a handful of windows, so the O(n²) scan
     * is trivial.
     *
     * #1149 review N3: the active root and the roots come from ONE enumeration ([LiveRoots]) — a
     * separate `getLiveNativeRoot()` read could see a platform window that became active in between
     * and is absent from the list, wrongly reading as "no active platform window".
     */
    fun getLiveWindowRoots(): LiveRoots {
        val service = serviceRef?.get() ?: return LiveRoots(null, emptyList())
        val rootInActive = service.rootInActiveWindow
        val roots = mutableListOf<AccessibilityNodeInfo>()
        var unreadable = 0
        var flaggedActiveRoot: AccessibilityNodeInfo? = null
        val windows = service.windows ?: emptyList()
        // PR #1155 review FF2: the ONE owner of "which window is active" ([activeFromEnumeration]).
        val activeFlags = activeFromEnumeration(windows)
        val flaggedActive = activeFlags.single
        for (window in windows) {
            val root = window.root
            if (window === flaggedActive) flaggedActiveRoot = root
            if (root != null) { roots.add(root); continue }
            // R3: only an APPLICATION window that is not picture-in-picture can hide a platform twin (an
            // IME, SystemUI or our overlay cannot). Residual: its package is unknown, so a foreign app's
            // unreadable window still counts.
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.isInPictureInPictureMode) continue
            // U1 (supersedes T4): the ACTIVE window is skipped only when its root IS represented — i.e.
            // rootInActiveWindow read it (same window id). An active application window with no readable
            // root is COUNTED: it is absent from `roots`, and a background twin must not become the sole
            // candidate.
            if (window.isActive && rootInActive != null && rootInActive.windowId == window.id) continue
            unreadable++
        }
        // U2: ONE source for "active" — the enumeration's own isActive flag (the root fetched in this pass).
        // rootInActiveWindow is used only when no enumerated window is flagged active; if both exist and
        // name different windows (focus moved between the two reads), there is NO active root for this
        // attempt — keep-all scoping, whose unreadable/cut/twin rules fail closed.
        val flagged = flaggedActive
        // PR #1155 review HH2 — keep #1149 U2's fail-closed rule: TWO or more windows flagged active
        // (a transition in flight) is an AMBIGUOUS identity → NO active root (keep-all scoping), never
        // a trusted rootInActiveWindow.
        val ambiguous = activeFlags.ambiguous
        val active: AccessibilityNodeInfo? = when {
            ambiguous -> null
            flagged == null -> rootInActive
            rootInActive != null && rootInActive.windowId != flagged.id -> null
            else -> flaggedActiveRoot ?: rootInActive?.takeIf { it.windowId == flagged.id }
        }
        rootInActive?.let { roots.add(0, it) }
        active?.let { roots.add(0, it) }
        val deduped = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) if (deduped.none { it == root }) deduped.add(root)
        return LiveRoots(active, deduped, unreadable)
    }

    /**
     * #1149 review N3 — one live window enumeration: the active root (any package) and all roots, active
     * first. [active] comes from the enumeration's own `isActive` flag (review U2; `rootInActiveWindow`
     * only as a fallback, and a disagreement between the two means NO active root). [unreadableWindows]
     * (review P3/R3/U1) counts enumerated non-PiP APPLICATION windows whose root came back null —
     * including the active one unless `rootInActiveWindow` represents it; their package is unknown.
     */
    /**
     * PR #1155 review FF2 — the ONE owner of "which window is active", for taps
     * ([getLiveWindowRoots], #1149 U2) and frames ([cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.window.snapshotForEvent],
     * the topology path) alike: the single window of [windows] flagged `isActive`, else null (none, or
     * more than one — a transition in flight). On the FRAME path the one fallback, used only when this
     * is null, is `rootInActiveWindow` (read the active root, with no overlay scan) — the pre-#1152
     * behaviour, never a refusal. On the TAP path ≥ 2 flagged means NO active root (HH2, #1149 U2).
     */
    fun activeFromEnumeration(windows: List<AccessibilityWindowInfo>): ActiveFlags {
        // JJ5: ONE isActive pass serves both the single window and the ambiguity check.
        val flagged = windows.filter { it.isActive }
        return ActiveFlags(single = flagged.singleOrNull(), ambiguous = flagged.size >= 2)
    }

    /** [activeFromEnumeration]'s one pass: the single flagged window (else null), and whether ≥ 2 were flagged. */
    data class ActiveFlags(val single: AccessibilityWindowInfo?, val ambiguous: Boolean)

    /**
     * PR #1155 review HH1 — the ONE three-valued active resolution, shared by the event path
     * (`snapshotForEvent`) and the topology path, so they can never disagree on one list:
     * - [Enabled] / [NotEnabled] — the single flagged window ([activeFromEnumeration]) whose FRESH
     *   root ([rootOf]) is readable with a non-null package, split by `isEnabled(package)`;
     * - [Unknown] with a [Unknown.window] — flagged, but its root is unreadable or package-less (never
     *   treated as "not enabled"); the overlay scan still runs off the window's LAYER;
     * - [Unknown] with no window — none flagged, or ≥ 2 (a transition in flight, HH2).
     */
    sealed interface ActiveWindow {
        data class Enabled(val window: AccessibilityWindowInfo, val root: AccessibilityNodeInfo) : ActiveWindow
        data class NotEnabled(val window: AccessibilityWindowInfo, val root: AccessibilityNodeInfo?) : ActiveWindow
        data class Unknown(val window: AccessibilityWindowInfo?) : ActiveWindow
    }

    /** [ActiveWindow] over ONE enumeration — see its KDoc (HH1). */
    fun resolveActive(windows: List<AccessibilityWindowInfo>, isEnabled: (String?) -> Boolean): ActiveWindow {
        val window = activeFromEnumeration(windows).single ?: return ActiveWindow.Unknown(null)
        val root = rootOf(window) ?: return ActiveWindow.Unknown(window)
        val pkg = root.packageName?.toString() ?: return ActiveWindow.Unknown(window)
        return if (isEnabled(pkg)) ActiveWindow.Enabled(window, root) else ActiveWindow.NotEnabled(window, root)
    }

    data class LiveRoots(
        val active: AccessibilityNodeInfo?,
        val roots: List<AccessibilityNodeInfo>,
        val unreadableWindows: Int = 0,
    )

    // --- 2. The Service Connection (Pull) ---
    // We use a WeakReference so we don't leak the Service if it restarts
    private var serviceRef: WeakReference<AccessibilityService>? = null

    fun registerService(service: AccessibilityService) {
        serviceRef = WeakReference(service)
    }

    /**
     * The live service instance, for service-level capabilities beyond node access
     * (e.g. `takeScreenshot`). Prefer the narrower accessors when one fits — this exists
     * so consumers inject this source instead of reaching for service statics (#349).
     */
    fun getService(): AccessibilityService? = serviceRef?.get()

    /** This app's own package (the service's), or null when unbound — our bubble's windows. */
    fun ownPackage(): String? = serviceRef?.get()?.packageName

    /**
     * A window's root snapshot: the converted [UiNode] tree + the **real package** owning it, plus
     * the window's metadata on the paths that already hold the window object (the foreground and
     * windows-changed paths, #1148 D4); null on the active-root path (review H4).
     */
    data class RootSnapshot(
        val tree: UiNode,
        val packageName: String?,
        val windowContext: TreeSnapshot.WindowContext? = null,
    )

    /**
     * Snapshots the active window's root as a [UiNode] tree plus the **real package that owns that
     * window** — read from the native root and captured together. Use this instead of a bare tree so
     * a snapshot is attributed to the window actually on screen, not to a triggering event from a
     * different app. Lets callers drop non-target windows (e.g. our own bubble overlay) rather than
     * mislabeling them as the platform — the #4 self-recognition feedback loop.
     *
     * SAFE to call from background threads.
     */
    fun getCurrentRootSnapshot(): RootSnapshot? {
        val root = getLiveNativeRoot() ?: return null
        return getCurrentRootSnapshot(root)
    }

    /**
     * [getCurrentRootSnapshot] over an ALREADY-FETCHED active [root] (#1148 review G5) — the
     * resolver reads the active root once for its package gate and maps that same node, saving a
     * binder call per frame and removing the read-to-read swap window.
     */
    fun getCurrentRootSnapshot(root: AccessibilityNodeInfo): RootSnapshot? {
        val tree = try {
            root.toUiNode()
        } catch (_: Exception) {
            null
        } ?: return null
        return RootSnapshot(
            tree = tree,
            packageName = root.packageName?.toString(),
            // #1148 review H4: no WindowContext on the active-root path — locating it cost a
            // `service.windows` enumeration per frame for metadata nothing persists.
            windowContext = null,
        )
    }

    /**
     * A window located by [foregroundWindow], carried with its ALREADY-FETCHED root and the size of
     * the enumeration it came from, so the caller maps it without a second `getWindows()` /
     * `window.root` binder round-trip (#1148 review F1).
     */
    data class LocatedWindow(
        val window: AccessibilityWindowInfo,
        val root: AccessibilityNodeInfo,
        val totalWindowCount: Int,
    )

    /** [foregroundWindow]'s verdict: the window in front, or why none is read (#1148 review H3). */
    sealed interface Foreground {
        data class Found(val located: LocatedWindow) : Foreground
        /**
         * [possibleOverlay] (PR #1155 review DD6): the refusal is an unreadable window that may be a
         * platform offer overlay — a LARGE `TYPE_SYSTEM` window whose owner cannot be read, or a
         * selected overlay whose root vanished. Only such a refusal may drop a frame on the EVENT
         * path; an unreadable APPLICATION window cannot be an offer overlay.
         */
        data class Refused(
            val reason: ForegroundSkipReason,
            val possibleOverlay: Boolean = false,
        ) : Foreground
    }

    /**
     * The window the dasher actually sees in FRONT, when a non-enabled window (our bubble, the
     * launcher, system UI) is the active one (#1148 review G5). Enumerated ONCE; each inspected
     * window's root fetched once.
     *
     * Candidates, by `layer` descending: `TYPE_APPLICATION` windows, EXCEPT our own (this app's
     * package — the bubble is never "another app in front") and picture-in-picture windows (review
     * H2: a Google Maps PiP floats above the fullscreen activity with a foreign package, and would
     * otherwise refuse every frame while the bubble is active), PLUS platform offer overlays
     * ([FrontWindowWalk.overlayProbe], #1152 D4: a `TYPE_SYSTEM` window of ≥ [MIN_OVERLAY_AREA_FRACTION] of the
     * display owned by a [Platform.offerOverlay] package). Every other system-layer window is never
     * a candidate (#1148 review H1: a platform's own transient toast would otherwise hijack frames) —
     * a small one never has its root fetched at all (size is checked before package), and a large
     * one's package is fetched once per window id ([WindowVerdictCache]).
     *
     * The FIRST candidate decides — readable-top-or-refuse: a null root → [Foreground.Refused]
     * `FRONT_UNREADABLE` (fail closed: we cannot verify what is on top, so never fall through to a
     * lower readable window); a package that fails [isEnabled] → `FRONT_NOT_ENABLED` (another app is
     * in front); else [Foreground.Found]. A DISABLED overlay platform's overlay is NOT a candidate
     * (PR #1155 review BB6 — skipped, the window beneath is read); a LARGE system window whose owner
     * cannot be read refuses `FRONT_UNREADABLE` (BB1). No
     * candidate at all → `NO_CANDIDATE`. A cached package lets an own/non-enabled application window
     * decide without a root fetch; a window that is READ always has its package re-checked on the
     * freshly-fetched root.
     *
     * SAFE to call from background threads.
     */
    fun foregroundWindow(isEnabled: (String?) -> Boolean): Foreground = try {
        foregroundWindow(getWindows(), isEnabled)
    } catch (_: Exception) {
        Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
    }

    /** [foregroundWindow] over an ALREADY-enumerated [windows] list (one enumeration per frame). */
    fun foregroundWindow(
        windows: List<AccessibilityWindowInfo>,
        isEnabled: (String?) -> Boolean,
        display: Lazy<Long> = walk.lazyDisplayArea(),
    ): Foreground = walk.frontOf(windows, isEnabled, windows.size, display)


    /** CC9: one event-path overlay scan (the per-event enumeration this feature costs), sized in the field. */
    internal fun onOverlayScan() = stats.onOverlayScan()

    /**
     * PR #1155 review FF1 — the active window's root, read from THAT enumerated window (never a second,
     * unsynchronised `rootInActiveWindow` read), with its package recorded in the verdict cache under
     * the generation read before the fetch. Null when unreadable (the caller falls back to
     * `rootInActiveWindow` with no overlay scan).
     */
    internal fun rootOf(w: AccessibilityWindowInfo): AccessibilityNodeInfo? {
        val gen = packageCache.generation
        val root = try {
            w.root
        } catch (_: Exception) {
            null
        } ?: return null
        root.packageName?.toString()?.let { packageCache.putPackage(w.id, it, gen) }
        return root
    }

    /**
     * [FrontWindowWalk.overlayFront]'s verdict (#1152 D5 as reworked by PR #1155 reviews BB5 … FF1) — **an enabled
     * platform offer overlay in front of an enabled active window is the frame**, whichever window
     * fired, so the covered window never interleaves with it (the #1148 F1 class). It is on top by
     * construction, so this is never the hidden-activity shape F1 removed.
     * - [Overlay] — an enabled overlay IS the front window above the active one: read it;
     * - [None] — no overlay in front: read the active root. Covers an application window in front of
     *   the overlay (CC4), a DISABLED overlay platform's overlay (BB6, skipped), an unreadable
     *   APPLICATION window above (DD6 — it cannot be an offer overlay), an unknown display area (DD8,
     *   inconclusive) and any exception during the scan (DD7);
     * - [Refused] — skip the frame: an unreadable window that may BE an overlay (a LARGE system window,
     *   or a selected overlay whose root vanished → `FRONT_UNREADABLE`, CC7/DD6), or the walk ran out of
     *   root fetches (`SCAN_BUDGET`, CC5/DD4).
     * The active window and every decision come from ONE enumeration (FF1), so nothing is reconciled.
     */
    sealed interface OverlayScan {
        data object None : OverlayScan
        data class Overlay(val located: LocatedWindow) : OverlayScan
        data class Refused(val reason: ForegroundSkipReason) : OverlayScan
    }

    /**
     * [FrontWindowWalk.overlayProbe]'s three-valued outcome (PR #1155 review BB1): a verified candidate, a verified
     * non-candidate (wrong type, too small, a non-overlay package, no display area), or a LARGE
     * system window whose owner cannot be read — which a readable-top-or-refuse caller must treat as
     * "something unverifiable is on top", never as "nothing here".
     */
    internal sealed interface OverlayProbe {
        /** The verified owner, with the root when this probe had to fetch it. */
        class Candidate(val packageName: String, val root: AccessibilityNodeInfo?) : OverlayProbe
        data object NotCandidate : OverlayProbe
        data object Unreadable : OverlayProbe

        /**
         * DD8: the display area is unknown, so size — and with it the overlay question — cannot be
         * answered. INCONCLUSIVE, never "not a candidate": the event path reads the active root
         * (pre-#1152 behaviour), the bubble path refuses `NO_DISPLAY_AREA`.
         */
        data object NoDisplayArea : OverlayProbe

        /** CC5: the walk's root-fetch budget ran out before this window's owner could be read. */
        data object BudgetExhausted : OverlayProbe
    }

    /**
     * Maps an already-fetched [root] of [window] into a [RootSnapshot] attributed to the root's
     * real package (#4), with the window's [TreeSnapshot.WindowContext] ([totalWindowCount] from
     * the same enumeration). No binder call beyond the subtree map. Null when the map fails.
     */
    fun getWindowSnapshot(
        window: AccessibilityWindowInfo,
        root: AccessibilityNodeInfo,
        totalWindowCount: Int,
    ): RootSnapshot? {
        val tree = try {
            root.toUiNode()
        } catch (_: Exception) {
            null
        } ?: return null
        // PR #1155 review FF6/JJ3: overlay frames are counted in ONE place — this shared builder — and
        // the count means "an OFFER OVERLAY was read": a system-layer window counts only when it passes
        // the overlay probe (memoized — no fetch on the walk's own candidates). An ACTIVE system window
        // that is not one (a dragged puck) is still mapped (pre-#1152 behaviour) but not counted.
        if (window.type == AccessibilityWindowInfo.TYPE_SYSTEM &&
            walk.overlayProbe(window, walk.displayArea()) is OverlayProbe.Candidate
        ) {
            stats.onOverlaySnapshot()
        }
        return RootSnapshot(
            tree = tree,
            packageName = root.packageName?.toString(),
            windowContext = contextOf(window, totalWindowCount),
        )
    }

    // --- 3. Multi-Window Support ---

    /**
     * Returns all accessibility windows currently visible.
     * Requires `flagRetrieveInteractiveWindows` in the service config.
     */
    fun getWindows(): List<AccessibilityWindowInfo> {
        val service = serviceRef?.get() ?: return emptyList()
        return service.windows ?: emptyList()
    }

    companion object {
        /**
         * #1152 D2: an overlay candidate covers at least this fraction of the display. The Uber offer
         * overlay is ~85–92 %; the puck ~0.8 %, the status bar ~5 %, heads-up notifications and
         * toasts ≤ 10 %.
         */
        const val MIN_OVERLAY_AREA_FRACTION = 0.25

        /**
         * PR #1155 review CC5: discovery root fetches (binder round-trips) allowed per front-window
         * walk. A normal screen needs 1–4; a layout that needs more (e.g. dozens of large readable
         * non-overlay system windows) is refused `SCAN_BUDGET` rather than fetched and thrashing the
         * 64-entry verdict cache.
         */
        const val MAX_SCAN_ROOT_FETCHES = 8
    }

    /** The ONE [TreeSnapshot.WindowContext] builder (#1148 D4), used by every snapshot path. */
    private fun contextOf(window: AccessibilityWindowInfo, total: Int): TreeSnapshot.WindowContext =
        TreeSnapshot.WindowContext(
            windowId = window.id,
            windowType = window.type,
            windowTitle = window.title?.toString(),
            windowLayer = window.layer,
            isActive = window.isActive,
            isFocused = window.isFocused,
            totalWindowCount = total,
        )
}
