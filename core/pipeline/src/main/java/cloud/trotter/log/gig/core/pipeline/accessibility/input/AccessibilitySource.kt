package cloud.trotter.log.gig.core.pipeline.accessibility.input

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.accessibility.TreeSnapshot
import cloud.trotter.log.gig.core.pipeline.accessibility.mapper.toUiNode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccessibilitySource @Inject constructor(
    /** Counts overlay-candidate decisions (#1152) and mapper linkage refusals (#1164). */
    private val stats: PipelineStats,
) {

    /**
     * Stats-less construction for the unit tests that only exercise window/root plumbing. A
     * secondary constructor, not a default argument — the PipelineStats (PR #1066) doctrine: an
     * all-default primary would synthesize a second `@Inject`-annotated constructor Dagger rejects.
     */
    constructor() : this(PipelineStats())

    private val frameResolver = FrameWindowResolver(LiveFrameWindowAccess(this), stats, WindowVerdictCache())

    /** The shared resolver, also the substitution seam for live-adapter tests. */
    internal val frames get() = frameResolver

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
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) frames.invalidateTopology()
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
     * PR #1155 review FF2 — the ONE owner of "which window is active", for taps
     * ([getLiveWindowRoots], #1149 U2) and frames ([cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.snapshotForEvent],
     * the topology path) alike, in ONE `isActive` pass (review JJ5): the single flagged window (else
     * null), and whether ≥ 2 were flagged (a transition in flight). On the FRAME path, with no single
     * flagged window the native root's own window is located by id (review JJ2); on the TAP path ≥ 2
     * flagged means NO active root (HH2, #1149 U2).
     */
    fun activeFromEnumeration(windows: List<AccessibilityWindowInfo>): ActiveFlags {
        // JJ5: ONE isActive pass serves both the single window and the ambiguity check.
        val flagged = windows.filter { it.isActive }
        return ActiveFlags(single = flagged.singleOrNull(), ambiguous = flagged.size >= 2)
    }

    /** [activeFromEnumeration]'s one pass: the single flagged window (else null), and whether ≥ 2 were flagged. */
    data class ActiveFlags(val single: AccessibilityWindowInfo?, val ambiguous: Boolean)

    /**
     * #1149 review N3 — one live window enumeration: the active root (any package) and all roots, active
     * first. [active] comes from the enumeration's own `isActive` flag (review U2; `rootInActiveWindow`
     * only as a fallback, and a disagreement between the two — or ≥ 2 flagged windows, PR #1155 review
     * HH2 — means NO active root). [unreadableWindows] (review P3/R3/U1) counts enumerated non-PiP
     * APPLICATION windows whose root came back null — including the active one unless
     * `rootInActiveWindow` represents it; their package is unknown.
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
        val tree = mapNodeOrNull(root) ?: return null
        return RootSnapshot(
            tree = tree,
            packageName = root.packageName?.toString(),
            // #1148 review H4: no WindowContext on the active-root path — locating it cost a
            // `service.windows` enumeration per frame for metadata nothing persists.
            windowContext = null,
        )
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
        val tree = mapNodeOrNull(root) ?: return null
        return RootSnapshot(
            tree = tree,
            packageName = root.packageName?.toString(),
            windowContext = contextOf(window, totalWindowCount),
        )
    }

    /**
     * #1164 — the ONE accessibility mapping seam (both snapshot paths and the click path): a node
     * that fails to map costs that frame, never the sensing flow.
     * An ordinary `Exception` is counted under `mappingFailures` and logged at DEBUG with the
     * exception class only — a per-frame stack trace would flood the firehose.
     *
     * Beside `Exception`, only the API-MISMATCH linkage errors are caught: a missing method, field or
     * class (`IncompatibleClassChangeError` covers `NoSuchMethodError`/`NoSuchFieldError`/
     * `AbstractMethodError`; plus `NoClassDefFoundError`) — what an unguarded newer-SDK call throws on
     * an older device (#1161). They are deterministic, so retrying per frame can never succeed, and
     * letting them escape restart-loops the supervised flow with no frame ever admitted (#909). Every
     * other `Error` — a `VerifyError`, an `ExceptionInInitializerError`, a `VirtualMachineError` — is
     * an app defect or a dying VM and still propagates to the supervisor's ERROR. The WARN names the
     * error CLASS only: a message is not PII-safe by construction (principle 7).
     */
    fun mapNodeOrNull(node: AccessibilityNodeInfo): UiNode? = try {
        node.toUiNode()
    } catch (e: Exception) {
        stats.onMappingFailure()
        Timber.tag("Pipeline").d("Accessibility mapping failed (%s) — frame dropped and counted", e.javaClass.simpleName)
        null
    } catch (error: IncompatibleClassChangeError) {
        onApiMismatch(error)
    } catch (error: NoClassDefFoundError) {
        onApiMismatch(error)
    }

    private fun onApiMismatch(error: LinkageError): UiNode? {
        stats.onMapperLinkageRefusal()
        if (mapperLinkageWarned.compareAndSet(false, true)) {
            Timber.tag("Pipeline").w(
                "Accessibility mapper refused a frame (%s); frames are dropped and counted (#1164)",
                error.javaClass.simpleName,
            )
        }
        return null
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
        /** #1164: one WARN per process, including across source/service recreation. */
        private val mapperLinkageWarned = AtomicBoolean()

        /** Re-arm the once-per-process WARN — tests only (the gate is process-wide by design). */
        internal fun resetMapperLinkageWarnForTest() = mapperLinkageWarned.set(false)

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
