package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toUiNode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilitySource @Inject constructor() {

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
     * against a fresh [getLiveNativeRoot], not by index.) The list is a handful of windows, so the
     * O(n²) scan is trivial.
     */
    fun getLiveWindowRoots(): List<AccessibilityNodeInfo> {
        val service = serviceRef?.get() ?: return emptyList()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        service.rootInActiveWindow?.let { roots.add(it) }
        (service.windows ?: emptyList()).forEach { window -> window.root?.let { roots.add(it) } }
        val deduped = mutableListOf<AccessibilityNodeInfo>()
        for (root in roots) if (deduped.none { it == root }) deduped.add(root)
        return deduped
    }

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

    /**
     * A window's root snapshot: the converted [UiNode] tree + the **real package** owning it, plus
     * the window's metadata when the platform can supply it (#1148 D4 — every snapshot path fills
     * it; null only when the window could not be located in `service.windows`).
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
            windowContext = activeWindowContext(root),
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
        data class Refused(val reason: ForegroundSkipReason) : Foreground
    }

    /**
     * The window the dasher actually sees in FRONT, when a non-enabled window (our bubble, the
     * launcher, system UI) is the active one (#1148 review G5). Enumerated ONCE; each inspected
     * window's root fetched once.
     *
     * Candidates, by `layer` descending: `TYPE_APPLICATION` windows only, EXCEPT our own (this
     * app's package — the bubble is never "another app in front") and picture-in-picture windows
     * (review H2: a Google Maps PiP floats above the fullscreen activity with a foreign package, and
     * would otherwise refuse every frame while the bubble is active). System-layer windows are never
     * candidates and never have their root fetched (#1148 review H1: a platform's own transient
     * system-layer toast would otherwise hijack frames, and every SystemUI window would cost a
     * binder fetch per frame). Overlays that surface as accessibility `TYPE_SYSTEM` (Android's
     * `TYPE_APPLICATION_OVERLAY`, e.g. Uber's offer overlay) are an open question: #1152.
     *
     * The FIRST candidate decides — readable-top-or-refuse: a null root → [Foreground.Refused]
     * `FRONT_UNREADABLE` (fail closed: we cannot verify what is on top, so never fall through to a
     * lower readable window); a package that fails [isEnabled] → `FRONT_NOT_ENABLED` (another app is
     * in front); else [Foreground.Found]. No candidate at all → `NO_CANDIDATE`.
     *
     * SAFE to call from background threads.
     */
    fun foregroundWindow(isEnabled: (String?) -> Boolean): Foreground = try {
        val ownPkg = serviceRef?.get()?.packageName
        val windows = getWindows()
        val ordered = windows
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode }
            .sortedByDescending { it.layer }
        var verdict: Foreground = Foreground.Refused(ForegroundSkipReason.NO_CANDIDATE)
        for (w in ordered) {
            val root = w.root
            if (root == null) { // unreadable application window on top → refuse
                verdict = Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
                break
            }
            val pkg = root.packageName?.toString()
            if (ownPkg != null && pkg == ownPkg) continue // our own bubble is never "in front"
            verdict = if (isEnabled(pkg)) {
                Foreground.Found(LocatedWindow(w, root, windows.size))
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

    /**
     * Locates the metadata of the window the captured [activeRoot] belongs to, matched by the
     * root's OWN `windowId` (a local getter, no IPC) — never by `isActive`, which may already name
     * a different window if focus moved while the tree was being mapped (#1148 review F4). Null when
     * no window carries that id — fail-open, the context is diagnostic metadata and must never cost
     * a frame.
     */
    private fun activeWindowContext(activeRoot: AccessibilityNodeInfo): TreeSnapshot.WindowContext? = try {
        val rootWindowId = activeRoot.windowId
        val windows = getWindows()
        windows.firstOrNull { it.id == rootWindowId }?.let { contextOf(it, windows.size) }
    } catch (_: Exception) {
        null
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
