package cloud.trotter.log.gig.core.pipeline.accessibility.input

import cloud.trotter.log.gig.core.pipeline.PipelineStats
import cloud.trotter.log.gig.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.log.gig.core.pipeline.accessibility.OverlayRejectReason
import cloud.trotter.log.gig.domain.state.Platform

/** The shared bounded, readable-top-or-refuse walk over framework-independent handles. */
internal class FrontWindowWalk<W : Any, R : Any>(
    private val access: FrameWindowAccess<W, R>,
    private val cache: WindowVerdictCache,
    private val stats: PipelineStats,
) {
    /**
     * PR #1155 review DD11: the display area, read at most ONCE per resolution (on first use — a walk
     * that meets no system window never reads it) and passed down to every check of that resolution.
     */
    fun lazyDisplayArea(): Lazy<Long> = lazy(LazyThreadSafetyMode.NONE) { displayArea() }

    /**
     * PR #1155 review JJ10 — what the ONE walk STOPPED at, as data; each caller applies its own
     * one-line policy over it ([foreground] for the front-window read, [overlayFront] for the overlay
     * decision). No policy flag goes in, no reason is reverse-engineered out.
     * - [Stopped] — the first deciding window: an [Kind.APPLICATION] window (not ours, not PiP) or a
     *   [Kind.SYSTEM_CANDIDATE] (a size-passing `TYPE_SYSTEM` window of an ENABLED overlay platform, or
     *   one whose owner could not be read). [readable] false = its root/package could not be read.
     *   An application window decided from the verdict cache carries its [packageName] but no [root]
     *   (review FF3 — a caller that maps it fetches through [fetchRoot], charged to the walk's budget);
     * - [Exhausted] — the root-fetch budget ran out (CC5/DD4); [NoDisplayArea] — a system window on an
     *   unknown display (DD8); [NoCandidate] — nothing decided; [Failed] — the walk threw (DD7).
     */
    sealed interface WalkStop<out W : Any, out R : Any> {
        enum class Kind { APPLICATION, SYSTEM_CANDIDATE }

        class Stopped<W : Any, R : Any>(
            val window: W,
            val kind: Kind,
            val readable: Boolean,
            val packageName: String?,
            val root: R?,
            val total: Int,
            val fetchRoot: () -> RootFetch<R>,
        ) : WalkStop<W, R>

        data object Exhausted : WalkStop<Nothing, Nothing>
        data object NoDisplayArea : WalkStop<Nothing, Nothing>
        data object NoCandidate : WalkStop<Nothing, Nothing>
        data object Failed : WalkStop<Nothing, Nothing>
    }

    /** A budget-charged root fetch after the walk (FF3's deferred fetch). */
    sealed interface RootFetch<out R : Any> {
        data object Exhausted : RootFetch<Nothing>
        data object Unreadable : RootFetch<Nothing>
        data class Root<R : Any>(val root: R) : RootFetch<R>
    }

    /**
     * The window in front — readable-top-or-refuse (#1148 D4, #1152 D4): the policy the foreground
     * read and the topology path apply over [walk]. An unreadable window on top refuses
     * `FRONT_UNREADABLE`; another app in front refuses `FRONT_NOT_ENABLED`.
     */
    fun foreground(
        windows: List<W>,
        isEnabled: (String?) -> Boolean,
        display: Lazy<Long>,
        total: Int = windows.size,
        gen: Long,
    ): Foreground<W, R> = when (val stop = walk(windows, isEnabled, display, total, gen)) {
        WalkStop.Exhausted -> Foreground.Refused(ForegroundSkipReason.SCAN_BUDGET)
        WalkStop.NoDisplayArea -> Foreground.Refused(ForegroundSkipReason.NO_DISPLAY_AREA)
        WalkStop.NoCandidate -> Foreground.Refused(ForegroundSkipReason.NO_CANDIDATE)
        WalkStop.Failed -> Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
        is WalkStop.Stopped -> when {
            !stop.readable -> Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
            stop.kind == WalkStop.Kind.APPLICATION && !isEnabled(stop.packageName) ->
                Foreground.Refused(ForegroundSkipReason.FRONT_NOT_ENABLED)
            stop.root != null ->
                Foreground.Found(LocatedWindow(stop.window, stop.root, stop.total, overlay = stop.kind == WalkStop.Kind.SYSTEM_CANDIDATE))
            else -> when (val fetched = stop.fetchRoot()) { // a cached enabled application window
                RootFetch.Exhausted -> Foreground.Refused(ForegroundSkipReason.SCAN_BUDGET)
                RootFetch.Unreadable -> Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
                is RootFetch.Root ->
                    if (isEnabled(access.packageName(fetched.root))) {
                        Foreground.Found(LocatedWindow(stop.window, fetched.root, stop.total))
                    } else {
                        Foreground.Refused(ForegroundSkipReason.FRONT_NOT_ENABLED)
                    }
            }
        }
    }

    /**
     * [foreground] over the windows ABOVE [active] (PR #1155 review CC3/CC4): every window type with a
     * strictly greater `layer` (which already excludes the active window, HH6). [total] for the
     * snapshot's WindowContext is the whole enumeration.
     */
    fun frontAbove(
        windows: List<W>,
        active: W,
        isEnabled: (String?) -> Boolean,
        display: Lazy<Long>,
        gen: Long,
    ): Foreground<W, R> = foreground(windows.filter { access.layer(it) > access.layer(active) }, isEnabled, display, windows.size, gen)

    /**
     * PR #1155 review DD3 — the ONE "is an enabled overlay in front above the active window" policy,
     * shared by the event and topology paths: only an OVERLAY winner is ever returned.
     * - a readable SYSTEM candidate → [OverlayScan.Overlay];
     * - an unreadable SYSTEM candidate (a possible overlay we cannot verify) or an exhausted budget →
     *   [OverlayScan.Refused] (CC5/CC7/DD6: reading the covered window beneath would interleave it with
     *   the overlay across its animate-in / tear-down frames);
     * - an APPLICATION window in front (readable or not — it cannot be an offer overlay; CC4/DD6), an
     *   unknown display (DD8), nothing, or a throwing walk (DD7) → [OverlayScan.None]: the active root
     *   stays the ground truth (#1148).
     */
    fun overlayFront(
        windows: List<W>,
        active: W,
        isEnabled: (String?) -> Boolean,
        display: Lazy<Long>,
        gen: Long,
    ): OverlayScan<W, R> = try {
        when (val stop = walk(windows.filter { access.layer(it) > access.layer(active) }, isEnabled, display, windows.size, gen)) {
            WalkStop.Exhausted -> OverlayScan.Refused(ForegroundSkipReason.SCAN_BUDGET)
            is WalkStop.Stopped -> when {
                stop.kind == WalkStop.Kind.APPLICATION -> OverlayScan.None
                !stop.readable -> OverlayScan.Refused(ForegroundSkipReason.FRONT_UNREADABLE)
                else -> OverlayScan.Overlay(LocatedWindow(stop.window, stop.root!!, stop.total, overlay = true))
            }
            WalkStop.NoDisplayArea, WalkStop.NoCandidate, WalkStop.Failed -> OverlayScan.None
        }
    } catch (_: Exception) {
        OverlayScan.None // DD7: a throwing isEnabled / stale window means "no overlay"
    }

    /**
     * The ONE readable-top-or-refuse walk (by `layer`, descending) over application windows (own and
     * PiP skipped) and overlay candidates; the first deciding window stops it ([WalkStop]).
     */
    fun walk(
        windows: List<W>,
        isEnabled: (String?) -> Boolean,
        display: Lazy<Long>,
        total: Int,
        // PR #1155 review KK1: the generation the CALLER read BEFORE its enumeration — a clear landing
        // between `getWindows()` and this walk must discard every verdict decided on the old list.
        gen: Long,
    ): WalkStop<W, R> = try {
        val ownPkg = access.ownPackage()
        val ordered = windows
            .filter {
                !access.pip(it) &&
                    (access.kind(it) == WindowKind.APPLICATION || access.kind(it) == WindowKind.SYSTEM)
            }
            .sortedByDescending { access.layer(it) }
        // CC5: at most FrameWindowResolver.MAX_SCAN_ROOT_FETCHES root fetches per walk (DD4: every one charged).
        val budget = ScanBudget(FrameWindowResolver.MAX_SCAN_ROOT_FETCHES)
        val fetchCharged: (W) -> RootFetch<R> = { w ->
            if (!budget.take()) RootFetch.Exhausted else fetchRoot(w)?.let { RootFetch.Root(it) } ?: RootFetch.Unreadable
        }
        var stop: WalkStop<W, R> = WalkStop.NoCandidate
        for (w in ordered) {
            if (access.kind(w) == WindowKind.SYSTEM) {
                val displayArea = display.value // DD11: one read per resolution, on first use
                stop = when (val probe = overlayProbe(w, displayArea, budget, gen)) {
                    OverlayProbe.NotCandidate -> continue // small, or a verified non-overlay package
                    OverlayProbe.NoDisplayArea -> WalkStop.NoDisplayArea // DD8: never walk past it
                    OverlayProbe.BudgetExhausted -> WalkStop.Exhausted // CC5: never fall through
                    // BB1/HH3: a LARGE system window whose owner cannot be read may be an offer overlay.
                    OverlayProbe.Unreadable ->
                        WalkStop.Stopped(w, WalkStop.Kind.SYSTEM_CANDIDATE, false, null, null, total) { RootFetch.Unreadable }
                    is OverlayProbe.Candidate -> {
                        // BB6: a DISABLED overlay platform's overlay is not a candidate — read beneath it.
                        if (!isEnabled(probe.packageName)) continue
                        // CC10: null → the memo was stale and is corrected; not an overlay — keep walking.
                        decideOverlay(w, probe, total, gen, budget, displayArea, isEnabled) ?: continue
                    }
                }
                break // the first candidate (or an unverifiable one) decides
            }
            // Application window (#1148, cache-aware since #1152 D3).
            val cached = cache.get(access.id(w))?.packageName
            if (cached != null && ownPkg != null && cached == ownPkg) continue
            if (cached != null) {
                // FF3: decided from the cache — no fetch; a caller that maps it fetches (charged).
                stop = WalkStop.Stopped(w, WalkStop.Kind.APPLICATION, true, cached, null, total) { fetchCharged(w) }
                break
            }
            if (!budget.take()) { // CC5
                stop = WalkStop.Exhausted
                break
            }
            val root = access.root(w)
            if (root == null) { // unreadable application window on top
                stop = WalkStop.Stopped(w, WalkStop.Kind.APPLICATION, false, null, null, total) { RootFetch.Unreadable }
                break
            }
            val pkg = access.packageName(root)
            pkg?.let { cache.putPackage(access.id(w), it, gen) }
            if (ownPkg != null && pkg == ownPkg) continue // our own bubble is never "in front"
            stop = WalkStop.Stopped(w, WalkStop.Kind.APPLICATION, true, pkg, root, total) { RootFetch.Root(root) }
            break // the first candidate decides
        }
        stop
    } catch (_: Exception) {
        WalkStop.Failed
    }

    /**
     * An ENABLED overlay candidate that is the top candidate: its root (reused from the probe, else
     * fetched once, charged — DD4) is RE-VERIFIED (a memoized verdict is never trusted for a read). A
     * null or package-less fresh root → an unreadable [WalkStop.Stopped] (counted UNREADABLE, FF4/JJ4).
     * CC10: a fresh root naming a DIFFERENT package corrects the memo (counted PACKAGE_CHANGED); DD10:
     * if that package is itself an enabled overlay platform's it is used at once, otherwise null tells
     * the walk "not an overlay after all — keep walking".
     */
    private fun decideOverlay(
        w: W,
        probe: OverlayProbe.Candidate<R>,
        total: Int,
        gen: Long,
        budget: ScanBudget,
        displayArea: Long,
        isEnabled: (String?) -> Boolean,
    ): WalkStop<W, R>? {
        val unreadable = WalkStop.Stopped<W, R>(w, WalkStop.Kind.SYSTEM_CANDIDATE, false, null, null, total) { RootFetch.Unreadable }
        val root = probe.root ?: run {
            if (!budget.take()) return WalkStop.Exhausted
            fetchRoot(w) // HH3: throwing ≡ null
        } ?: run {
            stats.onOverlayRejected(OverlayRejectReason.UNREADABLE) // JJ4
            return unreadable
        }
        val live = access.packageName(root)
        if (live == null) { // FF4: a package-less fresh root is UNREADABLE, not a package change
            stats.onOverlayRejected(OverlayRejectReason.UNREADABLE)
            return unreadable
        }
        if (live != probe.packageName) {
            stats.onOverlayRejected(OverlayRejectReason.PACKAGE_CHANGED)
            val corrected = if (live in Platform.overlayPackages) {
                WindowVerdictCache.Verdict.CANDIDATE
            } else {
                WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM
            }
            cache.putVerdict(access.id(w), live, corrected, access.bounds(w), displayArea, gen)
            if (corrected != WindowVerdictCache.Verdict.CANDIDATE || !isEnabled(live)) return null
        }
        return WalkStop.Stopped(w, WalkStop.Kind.SYSTEM_CANDIDATE, true, live, root, total) { RootFetch.Root(root) }
    }


    /**
     * #1152 D2 — is [w] a platform offer overlay? TYPE, then SIZE, then PACKAGE (cheapest first):
     * 1. `TYPE_SYSTEM` and not picture-in-picture;
     * 2. its bounds cover ≥ [FrameWindowResolver.MIN_OVERLAY_AREA_FRACTION] of [displayArea] (the status bar, the nav
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
    fun overlayProbe(
        w: W,
        displayArea: Long,
        budget: ScanBudget? = null,
        // PR #1155 review DD9: the WALK's generation, read before its enumeration was used — a probe
        // on a window from an older list after a mid-walk clear must not write under the new one.
        gen: Long,
    ): OverlayProbe<R> {
        if (access.kind(w) != WindowKind.SYSTEM || access.pip(w)) return OverlayProbe.NotCandidate
        // CC1: an unknown display area admits nothing — checked BEFORE the memo, so a cached
        // CANDIDATE is never honoured without a measurable display. Not memoized (it can become known).
        if (displayArea <= 0L) return reject(OverlayRejectReason.NO_DISPLAY_AREA, OverlayProbe.NoDisplayArea)
        val bounds = access.bounds(w)
        // BB7: a DECIDED verdict is memoized per window id — no root fetch, no count — but (CC1) only
        // while the window's CURRENT bounds equal the bounds it was decided on.
        val entry = cache.get(access.id(w))
        // DD5: … and the display area it was decided against (a rotation / display change re-probes).
        if (entry?.verdict != null && entry.bounds == bounds && entry.displayArea == displayArea) {
            when (entry.verdict) {
                WindowVerdictCache.Verdict.TOO_SMALL, WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM -> return OverlayProbe.NotCandidate
                WindowVerdictCache.Verdict.CANDIDATE -> entry.packageName?.let { return OverlayProbe.Candidate<R>(it, null) }
            }
        }
        if (bounds.area().toDouble() < FrameWindowResolver.MIN_OVERLAY_AREA_FRACTION * displayArea) { // DD11: the one area definition
            cache.putVerdict(access.id(w), null, WindowVerdictCache.Verdict.TOO_SMALL, bounds, displayArea, gen)
            return reject(OverlayRejectReason.TOO_SMALL, OverlayProbe.NotCandidate)
        }
        var root: R? = null
        val pkg: String = entry?.packageName ?: run {
            if (budget != null && !budget.take()) return OverlayProbe.BudgetExhausted // CC5
            // Not memoized: an unreadable root is retried next frame.
            // PR #1155 review HH3: a THROWING root fetch on a window that passed type + size is a
            // possible overlay exactly like a null root — never a generic walk failure that the
            // event path would read beneath.
            val fetched = fetchRoot(w) ?: return reject(OverlayRejectReason.UNREADABLE, OverlayProbe.Unreadable)
            root = fetched
            access.packageName(fetched) ?: return reject(OverlayRejectReason.UNREADABLE, OverlayProbe.Unreadable)
        }
        if (pkg !in Platform.overlayPackages) {
            cache.putVerdict(access.id(w), pkg, WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM, bounds, displayArea, gen)
            return reject(OverlayRejectReason.NOT_OVERLAY_PLATFORM, OverlayProbe.NotCandidate)
        }
        cache.putVerdict(access.id(w), pkg, WindowVerdictCache.Verdict.CANDIDATE, bounds, displayArea, gen)
        return OverlayProbe.Candidate(pkg, root)
    }

    private fun reject(reason: OverlayRejectReason, outcome: OverlayProbe<R>): OverlayProbe<R> {
        stats.onOverlayRejected(reason)
        return outcome
    }

    fun displayArea(): Long = access.displayArea()

    /** PR #1155 review CC5 — a per-walk root-fetch allowance. Not thread-shared (one per walk). */
    class ScanBudget(private var left: Int) {
        /** Consumes one fetch; false when none is left. */
        fun take(): Boolean = if (left > 0) { left--; true } else false
    }

    /** A root fetch whose failure (a stale window, a dead binder) reads as unreadable, never throws (HH3). */
    private fun fetchRoot(w: W): R? = try {
        access.root(w)
    } catch (_: Exception) {
        null
    }
}
