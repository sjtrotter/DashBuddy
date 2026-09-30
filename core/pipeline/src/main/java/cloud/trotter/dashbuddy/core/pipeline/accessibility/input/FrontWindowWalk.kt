package cloud.trotter.dashbuddy.core.pipeline.accessibility.input

import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import cloud.trotter.dashbuddy.core.pipeline.PipelineStats
import cloud.trotter.dashbuddy.core.pipeline.accessibility.ForegroundSkipReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.OverlayRejectReason
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource.Companion.MAX_SCAN_ROOT_FETCHES
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource.Companion.MIN_OVERLAY_AREA_FRACTION
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource.Foreground
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource.LocatedWindow
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource.OverlayProbe
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource.OverlayScan
import cloud.trotter.dashbuddy.domain.state.Platform

/**
 * The front-window walk (#1152, PR #1155 review FF9 — extracted from [AccessibilitySource], principle
 * 3): the ONE readable-top-or-refuse walk (`frontOf`) behind the foreground read, the "above the
 * active window" read and the overlay decision; the overlay candidacy probe (TYPE → SIZE → PACKAGE)
 * over the [WindowVerdictCache]; the per-walk root-fetch budget; the display-area read. Pure decision
 * logic over already-enumerated windows — [AccessibilitySource] stays the I/O seam (events, service
 * handle, roots, snapshot mapping) and delegates here. The result types ([Foreground],
 * [LocatedWindow], [OverlayProbe], [OverlayScan]) stay declared on [AccessibilitySource], their public
 * home.
 *
 * [ownPackage] is this app's package (our bubble), [displayMetrics] the service's display metrics
 * (null when unbound); [cache] is the source's verdict cache (cleared by `AccessibilitySource.emit`).
 */
internal class FrontWindowWalk(
    private val ownPackage: () -> String?,
    private val cache: WindowVerdictCache,
    private val stats: PipelineStats,
    private val displayMetrics: () -> DisplayMetrics?,
) {

    /**
     * PR #1155 review DD11: the display area, read at most ONCE per resolution (on first use — a walk
     * that meets no system window never reads it) and passed down to every check of that resolution.
     */
    fun lazyDisplayArea(): Lazy<Long> = lazy(LazyThreadSafetyMode.NONE) { displayArea() }

    /**
     * The single window in front among the windows ABOVE [active] (PR #1155 review CC3/CC4): the same
     * readable-top-or-refuse rule as [foregroundWindow], over every window type, restricted to those
     * with a strictly greater `layer`. An application window (not ours, not PiP) above an overlay is
     * the front — the overlay is not frontmost; a LARGE unreadable system window is a barrier.
     * [total] for the snapshot's WindowContext is the whole enumeration.
     */
    fun frontAbove(
        windows: List<AccessibilityWindowInfo>,
        active: AccessibilityWindowInfo,
        isEnabled: (String?) -> Boolean,
        display: Lazy<Long> = lazyDisplayArea(),
        overlayOnly: Boolean = false,
    ): Foreground =
        frontOf(windows.filter { it.layer > active.layer }, isEnabled, windows.size, display, overlayOnly) // HH6: `layer >` already excludes the active window

    /** The ONE readable-top-or-refuse walk behind [foregroundWindow] and [frontAbove]. */
    fun frontOf(
        windows: List<AccessibilityWindowInfo>,
        isEnabled: (String?) -> Boolean,
        total: Int,
        display: Lazy<Long>,
        // FF3: the caller only ever maps an OVERLAY winner ([overlayFront]) — an application window
        // in front is never mapped, so its root is not needed once its package is known.
        overlayOnly: Boolean = false,
    ): Foreground = try {
        val ownPkg = ownPackage()
        val ordered = windows
            .filter {
                !it.isInPictureInPictureMode &&
                    (it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM)
            }
            .sortedByDescending { it.layer }
        val gen = cache.generation // CC1: read BEFORE any fetch; stale writes are discarded
        // CC5: at most MAX_SCAN_ROOT_FETCHES discovery root fetches per walk; exhaustion refuses.
        val budget = ScanBudget(MAX_SCAN_ROOT_FETCHES)
        var verdict: Foreground = Foreground.Refused(ForegroundSkipReason.NO_CANDIDATE)
        for (w in ordered) {
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                val displayArea = display.value // DD11: one read per resolution, on first use
                when (val probe = overlayProbe(w, displayArea, budget, gen)) {
                    OverlayProbe.NotCandidate -> continue // small, or a verified non-overlay package
                    // PR #1155 review DD8: with no display area the overlay question cannot be
                    // answered — never walk past a system window on an unknown display.
                    OverlayProbe.NoDisplayArea -> verdict = Foreground.Refused(ForegroundSkipReason.NO_DISPLAY_AREA)
                    // CC5: out of root fetches — the rest is unverifiable; never fall through.
                    OverlayProbe.BudgetExhausted -> verdict = Foreground.Refused(ForegroundSkipReason.SCAN_BUDGET)
                    // PR #1155 review BB1: a LARGE system window whose owner cannot be read may be an
                    // offer overlay — readable-top-or-refuse, exactly like an unreadable application
                    // window. Never read the window beneath it.
                    OverlayProbe.Unreadable ->
                        verdict = Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE, possibleOverlay = true)
                    is OverlayProbe.Candidate -> {
                        // PR #1155 review BB6: a DISABLED overlay platform's overlay is not a candidate
                        // — the dasher chose to ignore that platform; read what is beneath it.
                        if (!isEnabled(probe.packageName)) continue
                        // CC10: null → the memo was stale (the fresh root names another package) —
                        // corrected; this window is not an overlay after all, keep walking.
                        verdict = decideOverlay(w, probe, total, gen, budget, displayArea, isEnabled) ?: continue
                    }
                }
                break // the first candidate (or an unverifiable one) decides
            }
            // Application window (#1148, cache-aware since #1152 D3).
            val cached = cache.get(w.id)?.packageName
            if (cached != null && ownPkg != null && cached == ownPkg) continue
            if (cached != null && !isEnabled(cached)) {
                verdict = Foreground.Refused(ForegroundSkipReason.FRONT_NOT_ENABLED)
                break
            }
            if (cached != null && overlayOnly) {
                // PR #1155 review FF3: a cached ENABLED application window decides the overlay walk
                // ("no overlay in front") without a root fetch — the root would never be mapped.
                verdict = Foreground.Refused(ForegroundSkipReason.NO_CANDIDATE)
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
            pkg?.let { cache.putPackage(w.id, it, gen) }
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
     * `FRONT_NOT_ENABLED`); DD10: when the fresh package is itself an ENABLED overlay platform's the
     * corrected memo is used at once (Found on the fresh root), otherwise null tells the walk "not an
     * overlay after all — keep walking" (a disabled one is skipped, BB6). A fresh root with no
     * package cannot be verified → refuse unreadable.
     */
    private fun decideOverlay(
        w: AccessibilityWindowInfo,
        probe: OverlayProbe.Candidate,
        total: Int,
        gen: Long,
        budget: ScanBudget,
        displayArea: Long,
        isEnabled: (String?) -> Boolean,
    ): Foreground? {
        // PR #1155 review DD4: EVERY root fetch in a walk is charged — a memoized CANDIDATE carries
        // no root, so its revalidation fetch counts against the budget too.
        val root = probe.root ?: run {
            if (!budget.take()) return Foreground.Refused(ForegroundSkipReason.SCAN_BUDGET)
            fetchRoot(w) // HH3: throwing ≡ null — a possible overlay we cannot verify
        } ?: run {
            // JJ4: the unreadable revalidation is counted like the package-less case below.
            stats.onOverlayRejected(OverlayRejectReason.UNREADABLE)
            return Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE, possibleOverlay = true)
        }
        val live = root.packageName?.toString()
        if (live == null) { // FF4: a package-less fresh root is UNREADABLE, not a package change
            stats.onOverlayRejected(OverlayRejectReason.UNREADABLE)
            return Foreground.Refused(ForegroundSkipReason.FRONT_UNREADABLE, possibleOverlay = true)
        }
        if (live != probe.packageName) {
            stats.onOverlayRejected(OverlayRejectReason.PACKAGE_CHANGED)
            val corrected = if (live in Platform.overlayPackages) {
                WindowVerdictCache.Verdict.CANDIDATE
            } else {
                WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM
            }
            cache.putVerdict(w.id, live, corrected, boundsOf(w), displayArea, gen)
            // PR #1155 review DD10: a corrected memo is USED — if the fresh root is itself an enabled
            // overlay platform's, this IS the overlay in front (never read beneath a live overlay).
            // The fetch that found it was already charged (DD4).
            if (corrected == WindowVerdictCache.Verdict.CANDIDATE && isEnabled(live)) {
                return Foreground.Found(LocatedWindow(w, root, total))
            }
            return null
        }
        return Foreground.Found(LocatedWindow(w, root, total))
    }


    /**
     * PR #1155 review DD3 — the ONE "is an enabled overlay the front above an ENABLED active window"
     * rule, shared by the event path (`snapshotForEvent`) and the topology path, so the two can
     * never disagree: only an OVERLAY winner is returned. The event path owns the active window and
     * never reads a non-active application window, so a non-active application window in front (a
     * DoorDash sheet above its activity) is [OverlayScan.None] on BOTH paths — the topology path must
     * not emit it, or it interleaves with the activity the event path reads.
     *
     * CC4: EVERY window type above the active one, by layer — an application window (not ours, not
     * PiP) above the overlay means the overlay is not frontmost (→ None). CC5: a walk out of root
     * fetches refuses the frame. DD6 (narrowing CC7): only an unreadable window that may BE an
     * overlay — a LARGE `TYPE_SYSTEM` window, [Foreground.Refused.possibleOverlay] — refuses; an
     * unreadable APPLICATION window (our bubble tearing down, a platform popup, a foreign panel) cannot
     * be an offer overlay → None: the active enabled root stays the ground truth (#1148).
     */
    fun overlayFront(
        windows: List<AccessibilityWindowInfo>,
        active: AccessibilityWindowInfo,
        isEnabled: (String?) -> Boolean,
        display: Lazy<Long> = lazyDisplayArea(),
    ): OverlayScan = try {
        overlayFrontUnguarded(windows, active, isEnabled, display)
    } catch (_: Exception) {
        // PR #1155 review DD7: an exception during the scan (a stale AccessibilityWindowInfo, a
        // throwing isEnabled) means "no overlay" — the already-fetched active root is read (the
        // pre-#1152 behaviour), never a dropped frame. (frontOf's own catch yields a non-overlay
        // FRONT_UNREADABLE, which maps to None below for the same reason.)
        OverlayScan.None
    }

    private fun overlayFrontUnguarded(
        windows: List<AccessibilityWindowInfo>,
        active: AccessibilityWindowInfo,
        isEnabled: (String?) -> Boolean,
        display: Lazy<Long>,
    ): OverlayScan {
        return when (val front = frontAbove(windows, active, isEnabled, display, overlayOnly = true)) {
            // FF6: the only system-layer windows the walk ever finds are overlay candidates.
            is Foreground.Found ->
                if (front.located.window.type == AccessibilityWindowInfo.TYPE_SYSTEM) OverlayScan.Overlay(front.located) else OverlayScan.None
            is Foreground.Refused -> when {
                front.reason == ForegroundSkipReason.SCAN_BUDGET -> OverlayScan.Refused(front.reason)
                // CC7/DD6: a possible overlay we cannot verify refuses (reading the covered window
                // beneath would interleave it with the overlay across its animate-in / tear-down
                // frames); any other unreadable window is not a barrier on this path.
                front.reason == ForegroundSkipReason.FRONT_UNREADABLE && front.possibleOverlay -> OverlayScan.Refused(front.reason)
                else -> OverlayScan.None
            }
        }
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
    fun overlayProbe(
        w: AccessibilityWindowInfo,
        displayArea: Long,
        budget: ScanBudget? = null,
        // PR #1155 review DD9: the WALK's generation, read before its enumeration was used — a probe
        // on a window from an older list after a mid-walk clear must not write under the new one.
        gen: Long = cache.generation,
    ): OverlayProbe {
        if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM || w.isInPictureInPictureMode) return OverlayProbe.NotCandidate
        // CC1: an unknown display area admits nothing — checked BEFORE the memo, so a cached
        // CANDIDATE is never honoured without a measurable display. Not memoized (it can become known).
        if (displayArea <= 0L) return reject(OverlayRejectReason.NO_DISPLAY_AREA, OverlayProbe.NoDisplayArea)
        val bounds = boundsOf(w)
        // BB7: a DECIDED verdict is memoized per window id — no root fetch, no count — but (CC1) only
        // while the window's CURRENT bounds equal the bounds it was decided on.
        val entry = cache.get(w.id)
        // DD5: … and the display area it was decided against (a rotation / display change re-probes).
        if (entry?.verdict != null && entry.bounds == bounds && entry.displayArea == displayArea) {
            when (entry.verdict) {
                WindowVerdictCache.Verdict.TOO_SMALL, WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM -> return OverlayProbe.NotCandidate
                WindowVerdictCache.Verdict.CANDIDATE -> entry.packageName?.let { return OverlayProbe.Candidate(it, null) }
            }
        }
        if (bounds.area().toDouble() < MIN_OVERLAY_AREA_FRACTION * displayArea) { // DD11: the one area definition
            cache.putVerdict(w.id, null, WindowVerdictCache.Verdict.TOO_SMALL, bounds, displayArea, gen)
            return reject(OverlayRejectReason.TOO_SMALL, OverlayProbe.NotCandidate)
        }
        var root: AccessibilityNodeInfo? = null
        val pkg: String = entry?.packageName ?: run {
            if (budget != null && !budget.take()) return OverlayProbe.BudgetExhausted // CC5
            // Not memoized: an unreadable root is retried next frame.
            // PR #1155 review HH3: a THROWING root fetch on a window that passed type + size is a
            // possible overlay exactly like a null root — never a generic walk failure that the
            // event path would read beneath.
            val fetched = fetchRoot(w) ?: return reject(OverlayRejectReason.UNREADABLE, OverlayProbe.Unreadable)
            root = fetched
            fetched.packageName?.toString() ?: return reject(OverlayRejectReason.UNREADABLE, OverlayProbe.Unreadable)
        }
        if (pkg !in Platform.overlayPackages) {
            cache.putVerdict(w.id, pkg, WindowVerdictCache.Verdict.NOT_OVERLAY_PLATFORM, bounds, displayArea, gen)
            return reject(OverlayRejectReason.NOT_OVERLAY_PLATFORM, OverlayProbe.NotCandidate)
        }
        cache.putVerdict(w.id, pkg, WindowVerdictCache.Verdict.CANDIDATE, bounds, displayArea, gen)
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
    fun packageOf(w: AccessibilityWindowInfo): String? {
        cache.get(w.id)?.packageName?.let { return it }
        val gen = cache.generation
        val pkg = w.root?.packageName?.toString() ?: return null
        cache.putPackage(w.id, pkg, gen)
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
    fun displayArea(): Long {
        // PR #1155 review DD5 (replaces CC9's per-generation memo): read once per RESOLUTION — a
        // display change need not produce a topology event (and release never bumps the generation
        // until #1151), and `resources.displayMetrics` is a cheap local read.
        val metrics = try {
            displayMetrics()
        } catch (_: Exception) {
            null
        }
        if (metrics != null && metrics.widthPixels > 0 && metrics.heightPixels > 0) {
            return metrics.widthPixels.toLong() * metrics.heightPixels.toLong()
        }
        return 0L
    }

    /** A window's on-screen area in px² (bounds are parceled with the window — no binder call). */
    fun areaOf(w: AccessibilityWindowInfo): Long = boundsOf(w).area() // DD11: one definition

    /** PR #1155 review CC5 — a per-walk root-fetch allowance. Not thread-shared (one per walk). */
    class ScanBudget(private var left: Int) {
        /** Consumes one fetch; false when none is left. */
        fun take(): Boolean = if (left > 0) { left--; true } else false
    }

    /** A root fetch whose failure (a stale window, a dead binder) reads as unreadable, never throws (HH3). */
    private fun fetchRoot(w: AccessibilityWindowInfo): AccessibilityNodeInfo? = try {
        w.root
    } catch (_: Exception) {
        null
    }
}
