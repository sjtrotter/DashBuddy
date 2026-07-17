package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.action.TargetExpectation
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.toBoundingBox
import cloud.trotter.dashbuddy.util.AccNodeUtils
import kotlinx.coroutines.delay
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes app-owned `RuleAction` taps on the platform app (#425).
 *
 * The target comes from the (untrusted, future-CDN #192) ruleset as a
 * [NodeRef] fingerprint, so the tap is verified at fire time against
 * app-owned anchors the ruleset cannot influence:
 *
 * 1. **Package scope** — only windows belonging to the platform's package are
 *    searched. No expected package → no tap (fail closed).
 * 2. **Label expectation** — the resolved node's subtree texts must satisfy
 *    the action's [TargetExpectation] (e.g. DECLINE_OFFER only taps a node
 *    labeled "Decline"). Platform buttons usually label via a child TextView,
 *    so collection walks a bounded subtree.
 * 3. **Active-window scoping** (#788) — the tap target normally lives in the
 *    active (topmost) window: the confirm sheet, the earnings summary. A twin
 *    node sharing the same view id can survive underneath it in a *lower*
 *    window (the offer popup's bare "Decline" behind the confirm sheet's
 *    "Decline offer"). Since [AccessibilitySource.getLiveWindowRoots] lists the
 *    active window first, when any label-verified candidate is in the active
 *    window we drop the other-window candidates before disambiguation — the
 *    implicit "click the first (active-window) candidate" that worked in the
 *    field pre-#770, made explicit. When the active window contributes none
 *    (e.g. the dasher's bubble holds focus and the target is in a background
 *    platform window) we keep them all and let the ranker decide.
 * 4. **Evidence-ranked disambiguation** (#600) — when more than one live node
 *    survives label verification *and active-window scoping*, [ClickCandidateRanker]
 *    picks the strongest match (exact stored text, then max bounds overlap)
 *    instead of a since-abandoned exact-bounds `==` comparison that broke under an
 *    animating sheet's temporal drift. If the ranker cannot decide
 *    ([ClickCandidateRanker.Tier.UNRESOLVED]) among >1 survivor, the tap is
 *    **aborted to manual** (#734) — clicking the first-in-tree candidate is
 *    luck, not verification. The abort is reserved for genuine SAME-window
 *    ambiguity: two distinct verified candidates within the active window.
 * 5. **Strict click** — self-or-ancestor only. The old clickable-*sibling*
 *    fallback is deliberately absent here: the verified node's sibling can be
 *    the opposite button (Accept sits beside Decline in the offer footer).
 *
 * Any check failing skips the tap and logs — the user acts manually instead.
 * The one exception is a transient **no-live-windows** read (#602): a
 * notification-action tap can reach this handler while a SystemUI takeover
 * (shade/lock) is still covering the platform app, so the very first read
 * finding no window is often just early, not wrong — see [awaitLiveRoots].
 */
@Singleton
class UiInteractionHandler @Inject constructor(
    private val accessibilitySource: AccessibilitySource
) {

    companion object {
        /** Max subtree depth scanned when collecting a candidate's labels. */
        private const val LABEL_SCAN_DEPTH = 3

        /** Max nodes visited per label scan — bounded ingestion of third-party UI. */
        private const val LABEL_SCAN_NODES = 24
    }

    /**
     * Re-resolve [ref] in the live tree (scoped to [expectedPackage]), verify
     * the node against [expectation], and click it.
     *
     * `suspend` since #602: the initial no-live-windows read is retried with
     * a bounded backoff (see [awaitLiveRoots]) before failing closed — every
     * other check here (candidates, label verification) is still a single
     * pass, not retried.
     *
     * @return true if a click action was dispatched to a verified node.
     */
    suspend fun performVerifiedClick(
        ref: NodeRef,
        expectedPackage: String?,
        expectation: TargetExpectation,
        description: String,
        allowRetry: Boolean = false,
    ): Boolean {
        Timber.tag("Effects").i("UiInteractionHandler: attempting verified click (%s)", description)

        if (expectedPackage.isNullOrEmpty()) {
            Timber.tag("Effects").w("No package scope for %s — refusing to click (fail closed)", description)
            return false
        }
        // #602: a notification-action tap can land here ~tens of ms after the
        // tap, while a SystemUI takeover (shade/lock) still owns the
        // foreground — the platform window reappears roughly 0.5-1s later
        // when the shade collapses. Retry the read (bounded) before failing
        // closed; nothing else in this function is retried.
        // #602: the bounded retry exists for USER-triggered taps (a notification
        // press races the shade collapse). AUTOMATION fires follow a live-screen
        // recognition milliseconds earlier — an empty enumeration there means the
        // window genuinely left; retrying could resolve against whatever screen
        // returns (#618 review F2). Single fail-closed read for AUTOMATION.
        val rootsSource = {
            accessibilitySource.getLiveWindowRoots()
                .filter { it.packageName?.toString() == expectedPackage }
        }
        val roots = if (allowRetry) awaitLiveRoots(expectedPackage, source = rootsSource) else rootsSource()
        if (roots.isEmpty()) {
            Timber.tag("Effects").w(
                "No live windows for package %s after %d retries over %dms — cannot click (%s)",
                expectedPackage, RETRY_DELAYS_MS.size, RETRY_DELAYS_MS.sum(), description,
            )
            return false
        }

        // #788: the active (topmost) window's root, used to scope candidates below.
        // `getLiveNativeRoot()` returns `rootInActiveWindow` — the same node
        // `getLiveWindowRoots()` puts first — so a root in `roots` that `==` this
        // (AccessibilityNodeInfo.equals = windowId+sourceNodeId) IS the active
        // window. Null when the active window belongs to another package (e.g. the
        // dasher's bubble holds focus) — it was package-filtered out of `roots`, so
        // no candidate matches and scoping is a no-op (we fall through to all
        // windows, as before).
        val activeRoot = accessibilitySource.getLiveNativeRoot()
        val candidates = findCandidates(roots, activeRoot, ref)
        if (candidates.isEmpty()) {
            Timber.tag("Effects").w(
                "Could not find any live node for: %s (id=%s, text=%s, bounds=%s)",
                description, ref.viewIdSuffix, ref.text, ref.boundsInScreen,
            )
            return false
        }

        // Label-verify once and keep each surviving node's labels alongside it —
        // the ranker below wants them too (for WARN diagnostics), so this avoids
        // walking each candidate's subtree twice (collectLabels is bounded but
        // not free).
        val labeledCandidates = candidates.mapNotNull { candidate ->
            val labels = collectLabels(candidate.node)
            if (expectation.matchesLabels(labels)) candidate to labels else null
        }
        if (labeledCandidates.isEmpty()) {
            Timber.tag("Effects").w(
                "%d candidate(s) for %s but NONE passed label verification (%s) — refusing to click",
                candidates.size, description, expectation.labelPattern,
            )
            return false
        }

        // #788: scope to the active window. A verified twin in a lower window (the
        // offer popup's "Decline" behind the confirm sheet) would otherwise tie
        // with the real target and abort the tap. If the active window contributed
        // any verified candidate, drop the rest before disambiguation; otherwise
        // (no active-window candidate — the target lives in a background platform
        // window) keep them all. Genuine SAME-window ambiguity still fails closed
        // below.
        val activeWindowCandidates = labeledCandidates.filter { it.first.inActiveWindow }
        val scopedCandidates = if (activeWindowCandidates.isNotEmpty()) {
            val dropped = labeledCandidates.size - activeWindowCandidates.size
            if (dropped > 0) {
                Timber.tag("Effects").d(
                    "Dropped %d other-window candidate(s) for %s (active window has %d)",
                    dropped, description, activeWindowCandidates.size,
                )
            }
            activeWindowCandidates
        } else {
            labeledCandidates
        }

        // Disambiguate (#600): rank the label-verified survivors by evidence —
        // exact stored text, then max bounds overlap — instead of exact-bounds
        // `==`, which dies to the temporal drift of an animating sheet (see
        // ClickCandidateRanker's KDoc for the full grounding).
        val verified = scopedCandidates.map { it.first.node }
        val facts = scopedCandidates.map { (candidate, labels) ->
            val liveBounds = Rect()
            candidate.node.getBoundsInScreen(liveBounds)
            ClickCandidateRanker.CandidateFacts(
                text = candidate.node.text?.toString(),
                labels = labels,
                bounds = liveBounds.toBoundingBox(),
            )
        }
        val ranked = ClickCandidateRanker.rank(ref, facts)
        val target = verified[ranked.index]
        when {
            ranked.tier == ClickCandidateRanker.Tier.UNRESOLVED && verified.size > 1 -> {
                // #734: an ambiguous target must NOT be clicked. Picking the
                // first-in-tree candidate is luck, not verification, and breaks
                // #425's fail-closed promise (a wrong click on a decline/accept
                // surface acts against the dasher's intent). Abort to manual,
                // matching the empty-candidate and label-fail arms above — the
                // under-constrained predicate is tightened at the ruleset so the
                // decisive single-candidate path is normally reached first.
                // WARN carries counts only — raw third-party UI text is DEBUG-tier
                // by Principle 7 (the WARN slice is user-exportable), #618 review F1.
                Timber.tag("Effects").w(
                    "No decisive match among %d verified candidates for: %s — refusing to click (fail closed)",
                    verified.size, description,
                )
                Timber.tag("Effects").d("Unresolved-tie candidate labels for %s: %s", description, facts.map { it.labels })
                return false
            }
            verified.size == 1 -> Timber.tag("Effects").d("Single verified candidate for %s — clicking it", description)
            else -> Timber.tag("Effects").d(
                "Resolved click target for %s via %s tier (%d candidate(s))",
                description, ranked.tier, verified.size,
            )
        }
        return AccNodeUtils.clickNodeStrict(target)
    }

    /**
     * A live candidate node plus whether it came from the active (topmost)
     * window's root — the flag the active-window scoping in [performVerifiedClick]
     * (#788) reads.
     */
    private data class Candidate(val node: AccessibilityNodeInfo, val inActiveWindow: Boolean)

    /**
     * Search the scoped roots, strongest strategy first (so a weak bounds
     * match in one window can't beat a viewId match in another), tagging each
     * hit with whether its source window is the active one ([activeRoot], `==`
     * by [AccessibilityNodeInfo.equals]). The target may live in a window other
     * than the active one — e.g. DoorDash's offer while the bubble holds focus —
     * so candidates from every scoped root are collected; the caller's
     * active-window scoping (#788) prefers the active window's, if any.
     */
    private fun findCandidates(
        roots: List<AccessibilityNodeInfo>,
        activeRoot: AccessibilityNodeInfo?,
        ref: NodeRef,
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        fun addFrom(root: AccessibilityNodeInfo, nodes: List<AccessibilityNodeInfo>) {
            val inActive = activeRoot != null && root == activeRoot
            for (node in nodes) candidates.add(Candidate(node, inActive))
        }
        // Strategy 1: find by view ID
        val targetId = ref.viewIdSuffix
        if (!targetId.isNullOrEmpty()) {
            for (root in roots) addFrom(root, root.findAccessibilityNodeInfosByViewId(targetId))
        }
        // Strategy 2: find by text
        val targetText = ref.text
        if (candidates.isEmpty() && !targetText.isNullOrEmpty()) {
            for (root in roots) addFrom(root, root.findAccessibilityNodeInfosByText(targetText))
        }
        // Strategy 3: walk each tree matching by bounds + className
        if (candidates.isEmpty()) {
            for (root in roots) {
                val found = mutableListOf<AccessibilityNodeInfo>()
                findNodeByBounds(root, ref.boundsInScreen, ref.classNameHint, found)
                addFrom(root, found)
            }
        }
        return candidates
    }

    /**
     * Collect the candidate's own text/contentDescription plus its bounded
     * subtree's — platform buttons typically carry their label on a child
     * TextView (e.g. DoorDash's `textView_prism_button_title`).
     */
    private fun collectLabels(node: AccessibilityNodeInfo): List<String> {
        val labels = mutableListOf<String>()
        var visited = 0
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > LABEL_SCAN_DEPTH || visited >= LABEL_SCAN_NODES) return
            visited++
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                visit(child, depth + 1)
            }
        }
        visit(node, 0)
        return labels
    }

    /**
     * Walk the accessibility tree looking for a node at the given bounds,
     * optionally matching className. Used when the node has no ID or text.
     */
    private fun findNodeByBounds(
        node: AccessibilityNodeInfo,
        targetBounds: BoundingBox,
        className: String?,
        out: MutableList<AccessibilityNodeInfo>,
    ) {
        val liveBounds = Rect()
        node.getBoundsInScreen(liveBounds)
        val matches = liveBounds.toBoundingBox() == targetBounds
            && (className == null || node.className?.toString() == className)
        if (matches) {
            out.add(node)
            return // no need to check children of a match
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByBounds(child, targetBounds, className, out)
        }
    }
}

/**
 * Bounded re-resolve delays for the [UiInteractionHandler.performVerifiedClick]
 * no-live-windows branch (#602). Total budget is 1500ms (<=1.5s per the build
 * plan) across <=3 retries — chosen to cover a SystemUI shade/lock takeover
 * collapsing (observed ~0.5-1s in the field) without stalling the side-effect
 * worker for long.
 */
private val RETRY_DELAYS_MS = longArrayOf(300L, 500L, 700L)

/**
 * Re-polls [source] — which must already return **package-scoped** live
 * window roots — retrying across [RETRY_DELAYS_MS] when a read comes back
 * empty, and returning as soon as one doesn't (#602).
 *
 * This wraps ONLY the "is the window there at all" read. It is intentionally
 * a free function taking the source as a lambda (not a method reaching for
 * [UiInteractionHandler]'s own [AccessibilitySource]) so it stays unit
 * testable without Robolectric or a live accessibility tree: the caller
 * (`performVerifiedClick`) decides what "package-scoped" means and supplies
 * it as [source]; the retry itself doesn't need to know.
 *
 * Retrying is correct here because an empty read right after a notification
 * tap is a **timing** artifact (the platform window hasn't been restored
 * yet), not a correctness denial — unlike label-verification failure or an
 * empty candidate list on an already-live window, which stay single-pass and
 * fail closed immediately (a live window with no matching node is a real
 * "the ruleset's target isn't there" case, not a race).
 */
internal suspend fun awaitLiveRoots(
    expectedPackage: String,
    source: () -> List<AccessibilityNodeInfo>,
): List<AccessibilityNodeInfo> {
    val first = source()
    if (first.isNotEmpty()) return first

    for (delayMs in RETRY_DELAYS_MS) {
        delay(delayMs)
        val roots = source()
        if (roots.isNotEmpty()) {
            Timber.tag("Effects").d("Live window for %s reappeared after a %dms retry", expectedPackage, delayMs)
            return roots
        }
    }
    return emptyList()
}
