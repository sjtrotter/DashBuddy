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
        var flaggedActive: AccessibilityWindowInfo? = null
        var flaggedActiveRoot: AccessibilityNodeInfo? = null
        val windows = service.windows ?: emptyList()
        for (window in windows) {
            val root = window.root
            if (window.isActive) { flaggedActive = window; flaggedActiveRoot = root }
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
        val active: AccessibilityNodeInfo? = when {
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
        /** A platform offer overlay (a11y `TYPE_SYSTEM`, #1152 D2), not an application window. */
        val isOverlay: Boolean = false,
    )

    /** [foregroundWindow]'s verdict: the window in front, or why none is read (#1148 review H3). */
    sealed interface Foreground {
        data class Found(val located: LocatedWindow) : Foreground
        data class Refused(val reason: ForegroundSkipReason) : Foreground
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
     * ([overlayProbe], #1152 D4: a `TYPE_SYSTEM` window of ≥ [MIN_OVERLAY_AREA_FRACTION] of the
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
    fun foregroundWindow(windows: List<AccessibilityWindowInfo>, isEnabled: (String?) -> Boolean): Foreground =
        frontOf(windows, isEnabled, windows.size)

    /**
     * The single window in front among the windows ABOVE [active] (PR #1155 review CC3/CC4): the same
     * readable-top-or-refuse rule as [foregroundWindow], over every window type, restricted to those
     * with a strictly greater `layer`. An application window (not ours, not PiP) above an overlay is
     * the front — the overlay is not frontmost; a LARGE unreadable system window is a barrier.
     * [total] for the snapshot's WindowContext is the whole enumeration.
     */
    internal fun frontAbove(
        windows: List<AccessibilityWindowInfo>,
        active: AccessibilityWindowInfo,
        isEnabled: (String?) -> Boolean,
    ): Foreground = frontOf(windows.filter { it.id != active.id && it.layer > active.layer }, isEnabled, windows.size)

    /** The ONE readable-top-or-refuse walk behind [foregroundWindow] and [frontAbove]. */
    private fun frontOf(
        windows: List<AccessibilityWindowInfo>,
        isEnabled: (String?) -> Boolean,
        total: Int,
    ): Foreground = try {
        val ownPkg = ownPackage()
        val ordered = windows
            .filter {
                !it.isInPictureInPictureMode &&
                    (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM)
            }
            .sortedByDescending { it.layer }
        var area: Long? = null // measured lazily — only when a system-layer window is inspected
        val gen = packageCache.generation // CC1: read BEFORE any fetch; stale writes are discarded
        // CC5: at most MAX_SCAN_ROOT_FETCHES discovery root fetches per walk; exhaustion refuses.
        val budget = ScanBudget(MAX_SCAN_ROOT_FETCHES)
        var verdict: Foreground = Foreground.Refused(ForegroundSkipReason.NO_CANDIDATE)
        for (w in ordered) {
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                val displayArea = area ?: displayArea().also { area = it }
                when (val probe = overlayProbe(w, displayArea, budget)) {
                    OverlayProbe.NotCandidate -> continue // small, or a verified non-overlay package
                    // CC5: out of root fetches — the rest is unverifiable; never fall through.
                    OverlayProbe.BudgetExhausted -> verdict = Foreground.Refused(ForegroundSkipReason.SCAN_BUDGET)
                    // PR #1155 review BB1: a LARGE system window whose owner cannot be read may be an
                    // offer overlay — readable-top-or-refuse, exactly like an unreadable application
                    // window. Never read the window beneath it.
                    OverlayProbe.Unreadable -> verdict = Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
                    is OverlayProbe.Candidate -> {
                        // PR #1155 review BB6: a DISABLED overlay platform's overlay is not a candidate
                        // — the dasher chose to ignore that platform; read what is beneath it.
                        if (!isEnabled(probe.packageName)) continue
                        // CC10: null → the memo was stale (the fresh root names another package) —
                        // corrected; this window is not an overlay after all, keep walking.
                        verdict = decideOverlay(w, probe, total, gen, budget) ?: continue
                    }
                }
                break // the first candidate (or an unverifiable one) decides
            }
            // Application window (#1148, cache-aware since #1152 D3).
            val cached = packageCache.get(w.id)?.packageName
            if (cached != null && ownPkg != null && cached == ownPkg) continue
            if (cached != null && !isEnabled(cached)) {
                verdict = Foreground.Refused(ForegroundSkipReason.FRONT_NOT_ENABLED)
                break
            }
            if (!budget.take()) { // CC5
                verdict = Foreground.Refused(ForegroundSkipReason.SCAN_BUDGET)
                break
            }
            val root = w.root
            if (root == null) { // unreadable application window on top → refuse
                verdict = Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
                break
            }
            val pkg = root.packageName?.toString()
            pkg?.let { packageCache.putPackage(w.id, it, gen) }
            if (ownPkg != null && pkg == ownPkg) continue // our own bubble is never "in front"
            verdict = if (isEnabled(pkg)) {
                Foreground.Found(LocatedWindow(w, root, total))
            } else {
                Foreground.Refused(ForegroundSkipReason.FRONT_NOT_ENABLED)
            }
            break // the first candidate decides
        }
        verdict
    } catch (_: Exception) {
        Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
    }

    /**
     * The verdict for an ENABLED overlay candidate that is the top candidate: its root (reused from
     * the probe, else fetched once) null → refuse unreadable; else found — with the package
     * RE-VERIFIED on the root that will be mapped (a memoized verdict is never trusted for a read).
     *
     * PR #1155 review CC10: a fresh root naming a DIFFERENT package means the memoized verdict was
     * stale — the memo is CORRECTED from the fresh root (so the next frame does not re-fetch and
     * refuse again), the refusal is counted truthfully as `overlayRejected{PACKAGE_CHANGED}` (never
     * `FRONT_NOT_ENABLED`), and null tells the walk "not an overlay after all — keep walking". A
     * fresh root with no package cannot be verified → refuse unreadable.
     */
    private fun decideOverlay(
        w: AccessibilityWindowInfo,
        probe: OverlayProbe.Candidate,
        total: Int,
        gen: Long,
        budget: ScanBudget,
    ): Foreground? {
        // PR #1155 review DD4: EVERY root fetch in a walk is charged — a memoized CANDIDATE carries
        // no root, so its revalidation fetch counts against the budget too.
        val root = probe.root ?: run {
            if (!budget.take()) return Foreground.Refused(ForegroundSkipReason.SCAN_BUDGET)
            w.root
        } ?: return Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
        val live = root.packageName?.toString()
        if (live != probe.packageName) {
            stats.onOverlayRejected(OverlayRejectReason.PACKAGE_CHANGED)
            if (live == null) return Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
            val corrected = if (live in Platform.overlayPackages) {
                WindowVerdictCache.Verdict.CANDIDATE
            } else {
                WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM
            }
            packageCache.putVerdict(w.id, live, corrected, boundsOf(w), gen)
            return null
        }
        return Foreground.Found(LocatedWindow(w, root, total, isOverlay = true))
    }

    /**
     * #1152 D5 as reworked by PR #1155 review BB5 — **an enabled platform offer overlay on top is
     * the frame**: the topmost ENABLED overlay candidate ([overlayProbe]) whose `layer` is strictly
     * greater than the layer of [activeWindowId]'s window (the window of the ALREADY-FETCHED active
     * root), whichever window fired the event. While it is up, EVERY content/state resolution reads
     * the overlay — so the covered window (the overlay platform's own map included) can never
     * interleave with it and flap R0 (the #1148 F1 class); FrameGate's identity dedup collapses the
     * repeats. Trade-off, accepted: the covered window's own transitions are not read while the
     * overlay covers it (the dasher is looking at the overlay). It is on top by construction, so this
     * is never the hidden-activity shape F1 removed.
     *
     * BB2 — the active identity is RECONCILED, never assumed: the active root's window must be in
     * this enumeration AND be the one (and only) window flagged active; otherwise → null (the ordinary
     * active-root path decides). The scan is [frontAbove] (CC4): EVERY window type above the active
     * one, by layer, first decides — the overlay is returned only if it IS that front window. An
     * application window above it (not ours, not PiP) → null; BB6 — a DISABLED overlay platform's
     * overlay is skipped; an unreadable window above the active one (a LARGE system window whose
     * owner cannot be read, or an application window) REFUSES the frame, `FRONT_UNREADABLE` (review
     * CC7 — identical to the bubble path, retried on the next frame); budget exhaustion refuses
     * `SCAN_BUDGET` (CC5). An overlay that IS the active window is not above it (the active-root
     * path reads it). Null on any failure. One enumeration; memoized verdicts (BB7) make the scan
     * cheap; the package is re-verified on the root that is mapped.
     */
    fun overlayAboveActive(activeWindowId: Int, isEnabled: (String?) -> Boolean): OverlayScan = try {
        stats.onOverlayScan() // CC9: the per-event enumeration this feature costs, sized in the field
        overlayAboveActive(getWindows(), activeWindowId, isEnabled)
    } catch (_: Exception) {
        OverlayScan.None
    }

    /** [overlayAboveActive]'s verdict: the overlay to read, nothing (read the active root), or refuse the frame. */
    sealed interface OverlayScan {
        data object None : OverlayScan
        data class Overlay(val located: LocatedWindow) : OverlayScan
        data class Refused(val reason: ForegroundSkipReason) : OverlayScan
    }

    private fun overlayAboveActive(
        windows: List<AccessibilityWindowInfo>,
        activeWindowId: Int,
        isEnabled: (String?) -> Boolean,
    ): OverlayScan {
        val active = windows.firstOrNull { it.id == activeWindowId } ?: return OverlayScan.None
        val flagged = windows.filter { it.isActive }
        if (flagged.size != 1 || flagged.single().id != active.id) return OverlayScan.None // unverifiable ordering
        return overlayFront(windows, active, isEnabled)
    }

    /**
     * PR #1155 review DD3 — the ONE "is an enabled overlay the front above an ENABLED active window"
     * rule, shared by the event path ([overlayAboveActive]) and the topology path, so the two can
     * never disagree: only an OVERLAY winner is returned. The event path owns the active window and
     * never reads a non-active application window, so a non-active application window in front (a
     * DoorDash sheet above its activity) is [OverlayScan.None] on BOTH paths — the topology path must
     * not emit it, or it interleaves with the activity the event path reads.
     *
     * CC4: EVERY window type above the active one, by layer — an application window (not ours, not
     * PiP) above the overlay means the overlay is not frontmost (→ None). CC5/CC7: a walk out of root
     * fetches, or an unreadable window on top, refuses the frame.
     */
    internal fun overlayFront(
        windows: List<AccessibilityWindowInfo>,
        active: AccessibilityWindowInfo,
        isEnabled: (String?) -> Boolean,
    ): OverlayScan {
        return when (val front = frontAbove(windows, active, isEnabled)) {
            is Foreground.Found -> if (front.located.isOverlay) OverlayScan.Overlay(front.located) else OverlayScan.None
            is Foreground.Refused -> when (front.reason) {
                // CC7: an unreadable window above the active one refuses the frame — the same as
                // the bubble path. Reading the active root beneath would interleave the covered
                // window with the overlay across its animate-in / tear-down (root-null) frames.
                ForegroundSkipReason.SCAN_BUDGET, ForegroundSkipReason.FRONT_UNREADABLE -> OverlayScan.Refused(front.reason)
                else -> OverlayScan.None
            }
        }
    }

    /**
     * [overlayProbe]'s three-valued outcome (PR #1155 review BB1): a verified candidate, a verified
     * non-candidate (wrong type, too small, a non-overlay package, no display area), or a LARGE
     * system window whose owner cannot be read — which a readable-top-or-refuse caller must treat as
     * "something unverifiable is on top", never as "nothing here".
     */
    internal sealed interface OverlayProbe {
        /** The verified owner, with the root when this probe had to fetch it. */
        class Candidate(val packageName: String, val root: AccessibilityNodeInfo?) : OverlayProbe
        data object NotCandidate : OverlayProbe
        data object Unreadable : OverlayProbe

        /** CC5: the walk's root-fetch budget ran out before this window's owner could be read. */
        data object BudgetExhausted : OverlayProbe
    }

    /** PR #1155 review CC5 — a per-walk root-fetch allowance. Not thread-shared (one per walk). */
    internal class ScanBudget(private var left: Int) {
        /** Consumes one fetch; false when none is left. */
        fun take(): Boolean = if (left > 0) { left--; true } else false
    }

    /**
     * #1152 D2 — is [w] a platform offer overlay? TYPE, then SIZE, then PACKAGE (cheapest first):
     * 1. `TYPE_SYSTEM` and not picture-in-picture;
     * 2. its bounds cover ≥ [MIN_OVERLAY_AREA_FRACTION] of [displayArea] (the status bar, the nav
     *    bar, the 142×142 Uber puck, heads-up notifications and toasts fail here WITHOUT a root
     *    fetch); an unknown display area (≤ 0) admits nothing — fail closed;
     * 3. its root's package is in [Platform.overlayPackages] (the registry flag, principle 8), read
     *    through [WindowVerdictCache] — a root is fetched at most once per window id; an unreadable
     *    root cannot prove its package and is refused.
     * Enablement is NOT decided here — callers read a candidate only when its package is enabled.
     * The sealed [OverlayProbe] is the ONE seam (PR #1155 review CC11 deleted the Boolean
     * `isOverlayCandidate`, which lost the [OverlayProbe.Unreadable] distinction). A [budget], when
     * given, bounds the root fetches of the walk it belongs to (CC5). Counts every refusal.
     */
    internal fun overlayProbe(w: AccessibilityWindowInfo, displayArea: Long, budget: ScanBudget? = null): OverlayProbe {
        if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM || w.isInPictureInPictureMode) return OverlayProbe.NotCandidate
        // CC1: an unknown display area admits nothing — checked BEFORE the memo, so a cached
        // CANDIDATE is never honoured without a measurable display. Not memoized (it can become known).
        if (displayArea <= 0L) return reject(OverlayRejectReason.NO_DISPLAY_AREA, OverlayProbe.NotCandidate)
        val gen = packageCache.generation // CC1: read BEFORE probing; a write after a clear is discarded
        val bounds = boundsOf(w)
        // BB7: a DECIDED verdict is memoized per window id — no root fetch, no count — but (CC1) only
        // while the window's CURRENT bounds equal the bounds it was decided on.
        val entry = packageCache.get(w.id)
        if (entry?.verdict != null && entry.bounds == bounds) {
            when (entry.verdict) {
                WindowVerdictCache.Verdict.TOO_SMALL, WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM -> return OverlayProbe.NotCandidate
                WindowVerdictCache.Verdict.CANDIDATE -> entry.packageName?.let { return OverlayProbe.Candidate(it, null) }
            }
        }
        val area = (bounds.right - bounds.left).coerceAtLeast(0).toLong() * (bounds.bottom - bounds.top).coerceAtLeast(0).toLong()
        if (area.toDouble() < MIN_OVERLAY_AREA_FRACTION * displayArea) {
            packageCache.putVerdict(w.id, null, WindowVerdictCache.Verdict.TOO_SMALL, bounds, gen)
            return reject(OverlayRejectReason.TOO_SMALL, OverlayProbe.NotCandidate)
        }
        var root: AccessibilityNodeInfo? = null
        val pkg: String = entry?.packageName ?: run {
            if (budget != null && !budget.take()) return OverlayProbe.BudgetExhausted // CC5
            // Not memoized: an unreadable root is retried next frame.
            val fetched = w.root ?: return reject(OverlayRejectReason.UNREADABLE, OverlayProbe.Unreadable)
            root = fetched
            fetched.packageName?.toString() ?: return reject(OverlayRejectReason.UNREADABLE, OverlayProbe.Unreadable)
        }
        if (pkg !in Platform.overlayPackages) {
            packageCache.putVerdict(w.id, pkg, WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM, bounds, gen)
            return reject(OverlayRejectReason.NOT_OVERLAY_PLATFORM, OverlayProbe.NotCandidate)
        }
        packageCache.putVerdict(w.id, pkg, WindowVerdictCache.Verdict.CANDIDATE, bounds, gen)
        return OverlayProbe.Candidate(pkg, root)
    }

    /** [w]'s current screen bounds (parceled with the window — no binder call). */
    private fun boundsOf(w: AccessibilityWindowInfo): WindowVerdictCache.Bounds {
        val r = Rect()
        w.getBoundsInScreen(r)
        return WindowVerdictCache.Bounds(r.left, r.top, r.right, r.bottom)
    }

    /**
     * [w]'s owning package through the cache (a miss fetches the root once and records it); null if
     * unreadable. The ONE owner of "a window's package" outside a read (PR #1155 review BB10).
     */
    internal fun packageOf(w: AccessibilityWindowInfo): String? {
        packageCache.get(w.id)?.packageName?.let { return it }
        val gen = packageCache.generation
        val pkg = w.root?.packageName?.toString() ?: return null
        packageCache.putPackage(w.id, pkg, gen)
        return pkg
    }

    private fun reject(reason: OverlayRejectReason, outcome: OverlayProbe): OverlayProbe {
        stats.onOverlayRejected(reason)
        return outcome
    }

    /**
     * The display area in px² (#1152 D2): the service's display metrics, else 0 — no overlay is
     * admitted (`NO_DISPLAY_AREA`, fail closed). PR #1155 review BB4: there is deliberately NO
     * window-bounds fallback — the largest application window can be a small PiP, which would make
     * the 142×142 puck "half the display" and a candidate.
     */
    internal fun displayArea(): Long {
        // PR #1155 review CC9: memoized per topology generation (a rotation / display change fires a
        // TYPE_WINDOWS_CHANGED, which bumps it); an unknown area is never memoized.
        val gen = packageCache.generation
        areaMemo?.let { (g, a) -> if (g == gen) return a }
        val metrics = try {
            serviceRef?.get()?.resources?.displayMetrics
        } catch (_: Exception) {
            null
        }
        if (metrics != null && metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            val area = metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
            areaMemo = gen to area
            return area
        }
        return 0L
    }

    /** CC9: `(generation, area)` of the last measured display. */
    @Volatile
    private var areaMemo: Pair<Long, Long>? = null

    /** A window's on-screen area in px² (bounds are parceled with the window — no binder call). */
    internal fun areaOf(w: AccessibilityWindowInfo): Long {
        val r = Rect()
        w.getBoundsInScreen(r)
        return r.width().coerceAtLeast(0).toLong() * r.height().coerceAtLeast(0).toLong()
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
