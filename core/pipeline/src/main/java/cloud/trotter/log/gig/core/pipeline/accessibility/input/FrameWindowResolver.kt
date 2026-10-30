package cloud.trotter.log.gig.core.pipeline.accessibility.input

import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.log.gig.core.pipeline.accessibility.event.type.window.EventSnapshot
import cloud.trotter.log.gig.domain.state.Platform
import timber.log.Timber

/** Owns frame acquisition, generation, bounded walks and the two post-acquisition policies. */
internal class FrameWindowResolver<W : Any, R : Any>(
    private val access: FrameWindowAccess<W, R>,
    private val stats: PipelineStats,
    private val cache: WindowVerdictCache,
) {
    internal val walk = FrontWindowWalk(access, cache, stats)
    internal val generation: Long get() = cache.generation
    internal val cacheForTest: WindowVerdictCache get() = cache

    fun invalidateTopology() = cache.clear()

    fun event(windowId: Int, eventPackage: String?, isEnabled: (String?) -> Boolean): EventSnapshot {
        // KK1: the verdict-cache generation is read BEFORE the enumeration and passed down.
        val gen = cache.generation
        // FF1: one enumeration decides "active" — only when an overlay could matter at all.
        val windows = if (Platform.overlayPackages.any(isEnabled)) {
            try {
                access.windows()
            } catch (_: Exception) {
                // PR #1155 review HH4: a THROWING enumeration is not an empty list — no enumeration to
                // reuse; the not-enabled branch re-enumerates inside foregroundWindow and reports
                // FRONT_UNREADABLE on failure, as #1148 did.
                null
            }
        } else {
            null
        }
        // DD11 + #1422 review S2: the display area is read once per resolution, and its lazy holder is created
        // only on a branch that scans or walks (the skip and native-map branches allocate nothing).
        val step: Step = if (windows == null) {
            preOverlayRead(access.nativeRoot(), null, gen, isEnabled, windowId, eventPackage)
        } else {
            resolveOnList(windows, gen, isEnabled, windowId, eventPackage)
        }
        val snapshot = when (step) {
            is Step.Skip -> return EventSnapshot.Skipped(step.reason)
            is Step.Frame -> step.snapshot
        } ?: return EventSnapshot.Skipped(ForegroundSkipReason.MAP_FAILED)

        if (!isEnabled(snapshot.packageName)) { // H5: mapping must preserve the gated root's package
            // A defended invariant fired (principle 7 → WARN, counts/packages only, no UI text).
            Timber.tag("Pipeline").w(
                "Post-map package mismatch: snapshot pkg=%s failed the gate its own root passed (event window=%d) — dropping",
                snapshot.packageName, windowId,
            )
            return EventSnapshot.Skipped(ForegroundSkipReason.POST_MAP_MISMATCH)
        }
        return EventSnapshot.Resolved(snapshot)
    }

    /** One resolution step's outcome: a (possibly failed-to-map) frame, or a counted skip. */
    private sealed interface Step {
        data class Frame(val snapshot: AccessibilitySource.RootSnapshot?) : Step
        data class Skip(val reason: ForegroundSkipReason) : Step
    }

    /**
     * Resolution over ONE enumeration (FF1/HH1/KK2). The active WINDOW is the single flagged one; with none
     * or ≥ 2 flagged (JJ2) the native root's OWN window, located in the list by its id — only a native root
     * not provably in the list takes the pre-#1152 read. The overlay scan runs FIRST, off that window's
     * layer (KK2: no active root is fetched while an enabled overlay is up); only when it finds nothing is
     * the active window classified ([resolveActive] / [classify]).
     */
    private fun resolveOnList(
        windows: List<W>,
        gen: Long,
        isEnabled: (String?) -> Boolean,
        windowId: Int,
        eventPackage: String?,
    ): Step {
        var nativeForWindow: R? = null
        val active = access.singleActive(windows) ?: run {
            // JJ2: the ONE KK2 exception: no single active flag requires the native root
            // BEFORE scanning to locate its window by ID. Reuse that root after the scan.
            val native = access.nativeRoot()
            val w = native?.let(access::rootWindowId)?.takeIf { it >= 0 }?.let { id -> windows.firstOrNull { access.id(it) == id } }
                ?: return preOverlayRead(native, windows, gen, isEnabled, windowId, eventPackage)
            nativeForWindow = native
            w
        }
        val provenNative = nativeForWindow
        val display = walk.lazyDisplayArea() // the overlay scan needs it; shared with every later read
        return scanThen(windows, active, gen, display, isEnabled, windowId) {
            val resolution = provenNative?.let { classify(active, it, isEnabled, gen) }
                ?: resolveActive(active, isEnabled, gen)
            when (eventAfterNoOverlay(eventActivePackage(resolution))) {
                // HH5/KK4: the window object is in hand — the ONE window builder maps it; a focused offer
                // overlay (the card IS the active window) is counted from its own probe verdict.
                FrameAction.MAP_ACTIVE ->
                    Step.Frame(mapWindow(active, (resolution as ActiveWindow.Enabled).root, windows.size, activeIsOverlay(active, display, gen)))
                FrameAction.FRONT_ALL -> frontRead(windows, gen, display, isEnabled, windowId, eventPackage)
                FrameAction.NATIVE_STAND_IN -> {
                    // II1/JJ1: the active window's own root was unreadable — ONE rootInActiveWindow read
                    // stands in only if it provably IS that window (same non-negative id); it then follows
                    // exactly the resolution its package calls for. Anything else → skip (the topology
                    // path emits nothing for the same case).
                    val native = access.nativeRoot()
                    if (native == null || access.rootWindowId(native) < 0 || access.rootWindowId(native) != access.id(active)) {
                        Timber.tag("Pipeline").v("🚫 Skip: active window root unreadable, no overlay in front (event window=%d)", windowId)
                        Step.Skip(ForegroundSkipReason.FRONT_UNREADABLE)
                    } else {
                        val matched = classify(active, native, isEnabled, gen)
                        when (eventAfterNoOverlay(eventActivePackage(matched))) {
                            FrameAction.MAP_ACTIVE ->
                                Step.Frame(mapWindow(active, (matched as ActiveWindow.Enabled).root, windows.size, activeIsOverlay(active, display, gen)))
                            FrameAction.FRONT_ALL -> frontRead(windows, gen, display, isEnabled, windowId, eventPackage)
                            FrameAction.NATIVE_STAND_IN -> Step.Skip(ForegroundSkipReason.FRONT_UNREADABLE)
                            else -> error("Not an event action")
                        }
                    }
                }
                else -> error("Not an event action")
            }
        }
    }

    /**
     * HH1: the overlay scan runs off the active WINDOW's layer (it never needs the root). An enabled
     * overlay in front is the frame; a refusal skips; no overlay → [onNone].
     */
    private inline fun scanThen(
        windows: List<W>,
        active: W,
        gen: Long,
        display: Lazy<Long>,
        noinline isEnabled: (String?) -> Boolean,
        windowId: Int,
        onNone: () -> Step,
    ): Step {
        stats.onOverlayScan()
        return when (val scan = walk.overlayFront(windows, active, isEnabled, display, gen)) {
            is OverlayScan.Refused -> {
                Timber.tag("Pipeline").v("🚫 Skip: overlay scan refused %s (event window=%d)", scan.reason, windowId)
                Step.Skip(scan.reason)
            }
            // PR #1155 review DD1 (reverses BB9): the walk SELECTED an overlay — a map failure does not
            // prove it left; the frame is skipped `MAP_FAILED` (retried on the next), never the window beneath.
            is OverlayScan.Overlay ->
                Step.Frame(mapWindow(scan.located))
            OverlayScan.None -> onNone()
        }
    }

    /** Our bubble / the launcher / a disabled platform is active → the window in front (#1148 D4). */
    private fun frontRead(
        windows: List<W>?,
        gen: Long,
        display: Lazy<Long>?,
        isEnabled: (String?) -> Boolean,
        windowId: Int,
        eventPackage: String?,
    ): Step {
        // Reuse THIS enumeration when there is one (FF1); else enumerate as #1148 did.
        val front = if (windows != null) walk.foreground(windows, isEnabled, display!!, gen = gen) else foregroundWindow(isEnabled)
        return when (front) {
            is Foreground.Refused -> {
                Timber.tag("Pipeline").v(
                    "🚫 Skip (pre-map): active window not enabled, front refused %s (event window=%d pkg=%s)",
                    front.reason, windowId, eventPackage,
                )
                Step.Skip(front.reason)
            }
            is Foreground.Found ->
                Step.Frame(mapWindow(front.located))
        }
    }

    /**
     * The pre-#1152 read over an already-fetched [activeRoot]: no overlay platform enabled (#1148 H4 — no
     * enumeration), a throwing enumeration (HH4), or a native root that is not provably in the list (JJ2).
     */
    private fun preOverlayRead(
        activeRoot: R?,
        windows: List<W>?,
        // KK1: the generation the caller read BEFORE producing [windows]; never re-read here (#1422 — a
        // re-read after the enumeration let a mid-frame clear file old-list verdicts under the new generation).
        gen: Long,
        isEnabled: (String?) -> Boolean,
        windowId: Int,
        eventPackage: String?,
    ): Step {
        if (activeRoot == null) {
            Timber.tag("Pipeline").v("🚫 Skip: no active root (event window=%d pkg=%s)", windowId, eventPackage)
            return Step.Skip(ForegroundSkipReason.NO_ACTIVE_ROOT)
        }
        return if (isEnabled(access.packageName(activeRoot))) {
            Step.Frame(access.mapNative(activeRoot))
        } else {
            frontRead(windows, gen, windows?.let { walk.lazyDisplayArea() }, isEnabled, windowId, eventPackage)
        }
    }

    /** Topology reads enablement once, AFTER enumeration and its diagnostics, as before. */
    fun topology(enabledPackages: () -> Set<String>): AccessibilitySource.RootSnapshot? {
        val gen = cache.generation // KK1: before the enumeration, including unknown-active paths
        val windows = access.windows()
        val display = walk.lazyDisplayArea()
        access.traceTopology(windows, display.value)
        val enabled = enabledPackages()
        val isEnabled: (String?) -> Boolean = { it in enabled }
        val ownPkg = access.ownPackage()
        val active = access.singleActive(windows)
        if (active == null) {
            stats.onTopologySkip(ForegroundSkipReason.NO_ACTIVE_ROOT)
            Timber.tag("Pipeline").v("🚫 Windows: no (single) active window — nothing emitted")
            return null
        }
        return when (val scan = walk.overlayFront(windows, active, isEnabled, display, gen)) {
            is OverlayScan.Overlay -> mapWindow(scan.located)
            is OverlayScan.Refused -> {
                stats.onTopologySkip(scan.reason)
                Timber.tag("Pipeline").v("🚫 Windows: overlay scan refused %s", scan.reason)
                null
            }
            OverlayScan.None -> {
                val resolution = resolveActive(active, isEnabled, gen)
                val state = when (resolution) {
                    is ActiveWindow.Enabled -> ActivePackage.ENABLED
                    is ActiveWindow.Unknown -> ActivePackage.UNREADABLE
                    is ActiveWindow.NotEnabled -> {
                        val isOwn = ownPkg != null && resolution.root?.let(access::packageName) == ownPkg
                        if (isOwn) ActivePackage.NOT_ENABLED_OWN else ActivePackage.NOT_ENABLED_OTHER
                    }
                }
                when (topologyAfterNoOverlay(state)) {
                    FrameAction.SKIP_UNREADABLE -> {
                        stats.onTopologySkip(ForegroundSkipReason.FRONT_UNREADABLE)
                        Timber.tag("Pipeline").v("🚫 Windows: active window unreadable, no overlay in front — nothing emitted")
                        null
                    }
                    FrameAction.QUIET -> {
                        Timber.tag("Pipeline").v("🚫 Windows: enabled active window, no overlay in front — nothing emitted")
                        null
                    }
                    FrameAction.FRONT_ALL, FrameAction.FRONT_ABOVE -> {
                        val front = if (state == ActivePackage.NOT_ENABLED_OWN) {
                            walk.foreground(windows, isEnabled, display, gen = gen)
                        } else {
                            walk.frontAbove(windows, active, isEnabled, display, gen)
                        }
                        when (front) {
                            is Foreground.Found -> mapWindow(front.located)
                            is Foreground.Refused -> {
                                stats.onTopologySkip(front.reason)
                                Timber.tag("Pipeline").v("🚫 Windows: nothing emitted in front (%s)", front.reason)
                                null
                            }
                        }
                    }
                    else -> error("Not a topology action")
                }
            }
        }
    }

    /** Fresh enumeration for the pre-overlay foreground fallback; a throwing list is unreadable. */
    internal fun foregroundWindow(isEnabled: (String?) -> Boolean): Foreground<W, R> = try {
        val gen = cache.generation
        val windows = access.windows()
        walk.foreground(windows, isEnabled, walk.lazyDisplayArea(), gen = gen)
    } catch (_: Exception) {
        Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
    }

    private fun resolveActive(window: W, isEnabled: (String?) -> Boolean, gen: Long): ActiveWindow<W, R> =
        rootOf(window, gen)?.let { classify(window, it, isEnabled, gen) } ?: ActiveWindow.Unknown(window)

    private fun classify(window: W, root: R, isEnabled: (String?) -> Boolean, gen: Long): ActiveWindow<W, R> {
        val pkg = access.packageName(root) ?: return ActiveWindow.Unknown(window)
        cache.putPackage(access.id(window), pkg, gen)
        return if (isEnabled(pkg)) ActiveWindow.Enabled(window, root) else ActiveWindow.NotEnabled(window, root)
    }

    private fun rootOf(window: W, gen: Long): R? {
        val root = try {
            access.root(window)
        } catch (_: Exception) {
            null
        } ?: return null
        access.packageName(root)?.let { cache.putPackage(access.id(window), it, gen) }
        return root
    }

    /** KK4: only the active system window needs a probe; walk winners carry their overlay fact. */
    private fun activeIsOverlay(window: W, display: Lazy<Long>, gen: Long): Boolean =
        access.kind(window) == WindowKind.SYSTEM &&
            walk.overlayProbe(window, display.value, gen = gen) is OverlayProbe.Candidate

    /** FF6/KK4: one mapping/counter owner; count only a successfully mapped overlay. */
    private fun mapWindow(window: W, root: R, total: Int, overlay: Boolean): AccessibilitySource.RootSnapshot? =
        access.mapWindow(window, root, total)?.also {
            if (overlay) stats.onOverlaySnapshot()
        }

    private fun mapWindow(located: LocatedWindow<W, R>): AccessibilitySource.RootSnapshot? =
        mapWindow(located.window, located.root, located.totalWindowCount, located.overlay)

    companion object {
        const val MIN_OVERLAY_AREA_FRACTION = 0.25
        const val MAX_SCAN_ROOT_FETCHES = 8
    }
}
