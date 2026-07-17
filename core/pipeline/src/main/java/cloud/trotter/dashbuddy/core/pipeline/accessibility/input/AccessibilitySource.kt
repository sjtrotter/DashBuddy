package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toUiNode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilitySource @Inject constructor() {

    // --- 1. The Event Stream (Push) ---
    private val _events = MutableSharedFlow<AccessibilityEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events = _events.asSharedFlow()

    fun emit(event: AccessibilityEvent) {
        _events.tryEmit(event)
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
     * The package owning the active window, read from the native root WITHOUT mapping
     * the tree (#435 item 3). A full [getCurrentRootSnapshot] converts every node with
     * one binder IPC apiece; the active-window pipelines drop non-target windows (our
     * own bubble overlay, the launcher, …) by package, so reading just the package
     * first lets them skip that whole mapping pass for a window they'll discard anyway.
     * Cheap: `rootInActiveWindow` fetches only the root node, not the subtree.
     *
     * This mirrors [WindowsChangedPipeline], which already reads `nativeRoot.packageName`
     * before converting. [getCurrentRootSnapshot] re-reads the active root and re-derives
     * the package, so a rare root swap between the two calls is still caught by the
     * caller's post-map package re-check — the #4 overlay-drop guarantee is unchanged.
     *
     * SAFE to call from background threads.
     */
    fun getActiveWindowPackage(): String? = getLiveNativeRoot()?.packageName?.toString()

    /** Active-window root snapshot: the converted [UiNode] tree + the **real package** owning it. */
    data class RootSnapshot(val tree: UiNode, val packageName: String?)

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
        val tree = try {
            root.toUiNode()
        } catch (_: Exception) {
            null
        } ?: return null
        return RootSnapshot(tree = tree, packageName = root.packageName?.toString())
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

    /**
     * Snapshots the UI tree rooted at a specific window's root node.
     * Use this to capture non-active windows (e.g., overlay offer screens).
     */
    fun getRootForWindow(window: AccessibilityWindowInfo): UiNode? {
        val root = window.root ?: return null
        return try {
            root.toUiNode()
        } catch (_: Exception) {
            null
        }
    }

}