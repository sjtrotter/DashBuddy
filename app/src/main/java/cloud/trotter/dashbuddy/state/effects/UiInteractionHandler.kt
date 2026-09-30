package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.action.TargetExpectation
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.NodeRef
import cloud.trotter.dashbuddy.core.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.TreeLimits
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
 *    "Decline offer"). Each candidate's source root is compared (`==`) against
 *    [AccessibilitySource.getLiveNativeRoot]; when any label-verified candidate
 *    is in the active window we drop the other-window candidates before
 *    disambiguation — the
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
 * 5. **Strict click** — the verified OWNER only (#1149). The old clickable-*sibling*
 *    fallback is deliberately absent here: the verified node's sibling can be
 *    the opposite button (Accept sits beside Decline in the offer footer).
 *
 * **Owner first (#1149).** Every candidate is mapped to its action owner
 * ([AccNodeUtils.resolveActionOwner]: `isClickable` OR an advertised `ACTION_CLICK`,
 * a bounded, cycle-safe self → parent walk) BEFORE any check above: owner-less
 * candidates are dropped, candidates sharing an owner are one control (not a #734
 * tie), labels are verified on the owner's subtree — which stops at every clickable
 * descendant, whose labels are its own (review I3) — and the owner is
 * `refresh()`ed BEFORE it is verified (review I1), then clicked with no further refresh — a
 * stale node is dropped, and nothing un-verified reaches dispatch. A hinted,
 * id-less bind is re-found by its EXACT subtree-label fingerprint (strategy 2b) BEFORE
 * the bounds walk: labels are identity, geometry is ranking evidence. Strategy 2b
 * honours the four constraints the withdrawn #1102 re-find left on record: exact
 * fingerprint (no superset), a depth/fetch-budget cut aborts the whole resolution,
 * every child fetch is budgeted before the call (nulls count), and label collection
 * never reads another package's embedded subtree (discovery and verification).
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
        /** #1093 — a clickable same-class node overlapping the ref this much is a bounds-walk candidate. */
        internal const val RELAXED_BOUNDS_IOU = 0.5

        /** Max subtree depth scanned when collecting a candidate's labels — one owner: [NodeRef.LABEL_SCAN_DEPTH] (#1149 I2). */
        private const val LABEL_SCAN_DEPTH = NodeRef.LABEL_SCAN_DEPTH

        /** Max child fetches per label scan — bounded ingestion; one owner: [NodeRef.LABEL_SCAN_NODES] (#1149 I2). */
        private const val LABEL_SCAN_NODES = NodeRef.LABEL_SCAN_NODES

        // #1149 review J5: the strategy-2b walk's depth / fetch bounds are the MAPPER's own tree
        // budget (TreeLimits — one owner): "incomplete" means a tree the mapper itself would have
        // truncated, never an arbitrary lower cut.
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
        // window. When the active window belongs to another package (e.g. the
        // dasher's bubble holds focus), this is non-null but owned by that other
        // package — it was package-filtered out of `roots`, so it matches nothing
        // and scoping no-ops (we fall through to all windows, as before).
        val activeRoot = accessibilitySource.getLiveNativeRoot()
        val search = findCandidates(roots, activeRoot, ref, expectedPackage)
        if (search.semanticTruncated) {
            // #1149 / #1102 review constraint 2: a label-only search that could not complete cannot
            // prove its survivors unique (the real control may sit past the cut, leaving one WRONG
            // survivor). Per window (review I6): this fires only when the active window has no
            // complete survivor; the bounds walk is then not a fallback.
            Timber.tag("Effects").w(
                "Semantic re-find for %s: a window's search was incomplete (bound depth %d / %d fetches, or an unreadable node) and the active window has no complete survivor — aborting to manual (#1149)",
                description, TreeLimits.MAX_TREE_DEPTH, TreeLimits.MAX_TREE_NODES,
            )
            return false
        }
        val candidates = search.candidates
        if (candidates.isEmpty()) {
            // Principle 7: `ref.text` is third-party UI text — DEBUG only, never the exportable WARN slice.
            Timber.tag("Effects").w(
                "Could not find any live node for: %s (id=%s, bounds=%s, hints=%d)",
                description, ref.viewIdSuffix, ref.boundsInScreen, ref.labelHintHashes.size,
            )
            Timber.tag("Effects").d("Unfound ref text for %s: %s", description, ref.text)
            return false
        }

        // #1149: resolve each candidate's ACTION OWNER first — the node a tap actually lands on —
        // and dedupe candidates that lead to the same owner (a button's title TextView and the
        // button are ONE control, not a #734 tie). Everything below verifies, scopes, ranks and
        // clicks OWNERS; verification never runs on one node and the click on another.
        val owned = resolveOwners(candidates, ref, expectedPackage)
        if (owned.orphaned > 0) {
            Timber.tag("Effects").w(
                "%d of %d candidate(s) for %s have no clickable, fresh owner in the package within %d steps (%d stale) — dropped (#1149)",
                owned.orphaned, candidates.size, description, AccNodeUtils.MAX_OWNER_WALK, owned.stale,
            )
        }
        if (owned.targets.isEmpty()) return false

        // Label-verify once, on the OWNER's bounded subtree, and keep each surviving owner's labels
        // alongside it — the ranker below wants them too (for WARN diagnostics), so this avoids
        // walking each subtree twice (collectLabels is bounded but not free).
        var geometryRejected = 0
        var staleEvidence = 0
        var semanticUnprovable = 0
        val labeledCandidates = owned.targets.mapNotNull { target ->
            // #1149 review J1: evidence labels must be as fresh and as scoped as the owner's. When the
            // matched node is not the owner, it is refreshed FIRST (a title rebinding Decline → Accept
            // between the id query and now is read as Accept), must belong to the scoped package, and
            // must still resolve to THIS owner (bounded walk, `==`) — else the target is dropped as
            // stale. Refreshing before the owner scan also means that scan reads the new text.
            val separateEvidence = target.evidence != target.owner
            if (separateEvidence && (
                    !target.evidence.refresh() ||
                        target.evidence.packageName?.toString() != expectedPackage ||
                        AccNodeUtils.resolveActionOwner(target.evidence) != target.owner
                    )
            ) { staleEvidence++; return@mapNotNull null }
            val scan = scanLabels(target.owner, expectedPackage)
            // #1149 review I8: the MATCHED node's own (refreshed, in-package — J1) text/contentDescription
            // always count — the owner may sit more than LABEL_SCAN_DEPTH levels (or LABEL_SCAN_NODES
            // fetches) above it, and a viewId/text match that verified pre-#1149 must not fail for that.
            // Consistent with I3: the evidence node is inside the owner and not itself clickable (else it
            // would BE the owner). Only the lenient expectation/ranking set grows; the semantic
            // fingerprint below reads the owner scan alone (for a 2b hit, evidence IS the owner).
            val evidenceLabels = if (!separateEvidence) emptyList() else listOfNotNull(
                target.evidence.text?.toString()?.takeIf { it.isNotBlank() },
                target.evidence.contentDescription?.toString()?.takeIf { it.isNotBlank() },
            )
            val labels = scan.labels + evidenceLabels.filterNot { it in scan.labels }
            // #1093: a bounds-derived candidate — exact rect or overlap — needs the bind's own
            // subtree labels among its live ones; that, not geometry, separates the slid receipt
            // row from whatever control now sits where the row was captured. A hint-less ref
            // (pre-#1093 snapshot) keeps the legacy exact-only behaviour. #1149: a semantic (2b)
            // candidate was FOUND by its exact label fingerprint; re-checked here on the owner.
            if (target.boundsDerived || target.semantic) {
                val identified = when {
                    target.semantic -> scan.complete && ref.fingerprintMatches(scan.labels)
                    ref.labelHintHashes.isEmpty() -> !target.relaxed
                    else -> ref.agreesWithLabels(labels)
                }
                if (!identified) {
                    // #1149 review J6: a 2b hit whose post-refresh scan is incomplete or no longer the
                    // fingerprint is UNPROVABLE, not merely absent — dropping it would hand its twin
                    // the tap as the sole survivor. Counted here; the whole tap aborts below.
                    if (target.semantic) semanticUnprovable++ else geometryRejected++
                    return@mapNotNull null
                }
            }
            if (!expectation.matchesLabels(labels)) return@mapNotNull null
            target to labels
        }
        if (semanticUnprovable > 0) {
            Timber.tag("Effects").w(
                "%d semantic candidate(s) for %s became unprovable after refresh — aborting to manual (#1149)",
                semanticUnprovable, description,
            )
            return false
        }
        if (staleEvidence > 0) {
            Timber.tag("Effects").w(
                "%d candidate(s) for %s dropped: the matched node went stale, left the package or no longer resolves to its owner (#1149)",
                staleEvidence, description,
            )
        }
        if (labeledCandidates.isEmpty()) {
            Timber.tag("Effects").w(
                "%d candidate(s) for %s but NONE passed verification (label %s; %d bounds-derived candidate(s) did not carry the bind's labels) — refusing to click",
                owned.targets.size, description, expectation.labelPattern, geometryRejected,
            )
            return false
        }

        val controls = labeledCandidates

        // #788: scope to the active window. A verified twin in a lower window (the
        // offer popup's "Decline" behind the confirm sheet) would otherwise tie
        // with the real target and abort the tap. If the active window contributed
        // any verified candidate, drop the rest before disambiguation; otherwise
        // (no active-window candidate — the target lives in a background platform
        // window) keep them all. Genuine SAME-window ambiguity still fails closed
        // below.
        val activeWindowCandidates = controls.filter { it.first.inActiveWindow }
        val scopedCandidates = if (activeWindowCandidates.isNotEmpty()) {
            val dropped = controls.size - activeWindowCandidates.size
            if (dropped > 0) {
                Timber.tag("Effects").d(
                    "Dropped %d other-window candidate(s) for %s (active window has %d)",
                    dropped, description, activeWindowCandidates.size,
                )
            }
            activeWindowCandidates
        } else {
            controls
        }

        // #1093 (review rounds 3–4): a VERIFIED walk-derived candidate nested inside another
        // VERIFIED one — a clickable wrapper at the captured rect with the row inside it — is
        // undecidable: the wrapper inherits the row's labels, and whichever overlaps more is not
        // evidence of which one is the control. Abort to manual rather than guess. An UNVERIFIED
        // descendant says nothing about its parent (a stray clickable child that carries only
        // one of the labels must not evict the row it sits in). Checked AFTER the #788 window
        // scoping, among the RETAINED candidates only: a nested pair in a background window must
        // not abort an unambiguous tap in the active one (round 4). #1149: the same rule covers
        // the semantic (2b) walk, whose hits record their nesting the same way.
        val retainedIndices = scopedCandidates.map { it.first.index }.toHashSet()
        val nested = scopedCandidates.firstOrNull { (t, _) -> t.ancestors.any { it in retainedIndices } }
        if (nested != null) {
            Timber.tag("Effects").w(
                "Nested verified candidates for %s (a control inside another that also carries the bind's labels) — aborting to manual (#1093)",
                description,
            )
            return false
        }

        // #1149 review I5: semantic TWINS abort. Two or more 2b survivors left after owner dedupe and
        // #788 scoping carry the same exact fingerprint; the ranker's overlap tier would pick between
        // them by the captured rect — the very evidence 2b exists to distrust. Only a decisive stored
        // `text` that exactly ONE survivor's evidence matches may break the tie. (Survivors from
        // different background windows — no active-window candidate — abort the same way.)
        if (scopedCandidates.size > 1 && scopedCandidates.any { it.first.semantic }) {
            val refText = ref.text?.takeIf { it.isNotBlank() }
            val byText = if (refText == null) emptyList() else
                scopedCandidates.filter { it.first.evidence.text?.toString()?.take(50) == refText }
            if (byText.size != 1) {
                Timber.tag("Effects").w(
                    "%d semantic twins for %s share the bind's fingerprint and no stored text decides — aborting to manual (#1149)",
                    scopedCandidates.size, description,
                )
                return false
            }
            Timber.tag("Effects").d("Semantic twins for %s resolved by exact stored text", description)
            return AccNodeUtils.clickNodeStrict(byText.single().first.owner, expectedPackage)
        }

        // Disambiguate (#600): rank the label-verified survivors by evidence —
        // exact stored text, then max bounds overlap — instead of exact-bounds
        // `==`, which dies to the temporal drift of an animating sheet (see
        // ClickCandidateRanker's KDoc for the full grounding). The text/bounds are
        // the MATCHED node's (#1149 `evidence`); the labels are the owner's.
        val verified = scopedCandidates.map { it.first }
        val facts = scopedCandidates.map { (target, labels) ->
            val liveBounds = Rect()
            target.evidence.getBoundsInScreen(liveBounds)
            ClickCandidateRanker.CandidateFacts(
                text = target.evidence.text?.toString(),
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
        return AccNodeUtils.clickNodeStrict(target.owner, expectedPackage)
    }

    /**
     * A live candidate node plus whether it came from the active (topmost) window's root — the
     * flag the active-window scoping in [performVerifiedClick] (#788) reads.
     *
     * [boundsDerived]: found by the bounds walk (strategy 3), exact rect or not — geometry is not
     * identity, so such a candidate must carry EVERY one of the ref's [NodeRef.labelHintHashes]
     * among its live labels to survive verification (#1093). [relaxed]: the overlap-only flavour
     * of that walk; a ref with NO hints (a pre-#1093 snapshot) admits an exact match only.
     * [semantic]: found by strategy 2b (#1149) — by those label hints, with no geometric entrance
     * test.
     */
    private data class Candidate(
        val node: AccessibilityNodeInfo,
        val inActiveWindow: Boolean,
        val boundsDerived: Boolean = false,
        val relaxed: Boolean = false,
        val semantic: Boolean = false,
        /** Indices (into the candidate list) of walk-derived candidates this one sits INSIDE. */
        val ancestors: List<Int> = emptyList(),
    )

    /**
     * #1149 — one candidate per distinct action OWNER. [owner] is what is verified and clicked;
     * [evidence] is the matched node whose own text/bounds feed [ClickCandidateRanker] (a
     * button's title TextView carries the exact stored text, the button does not). Provenance
     * is the strongest of the merged candidates: exact beats relaxed. [ancestors] are indices
     * (into the OWNER list, [index] being this one's) of owners this one is nested inside.
     */
    private data class OwnedTarget(
        val index: Int,
        val owner: AccessibilityNodeInfo,
        val evidence: AccessibilityNodeInfo,
        val inActiveWindow: Boolean,
        val boundsDerived: Boolean,
        val relaxed: Boolean,
        val semantic: Boolean,
        val ancestors: Set<Int>,
    )

    private class OwnerResolution(val targets: List<OwnedTarget>, val orphaned: Int, val stale: Int)

    /**
     * #1149 — map each candidate to its action owner ([AccNodeUtils.resolveActionOwner]), DROP
     * the owner-less and the foreign-package owners (nothing a scoped tap could land on), and DEDUPE candidates whose owners are `==`
     * (keeping the first, with the strongest provenance). Candidate nesting is re-expressed
     * between owners, so the #1093 nested-abort still sees a wrapper around a row.
     */
    private fun resolveOwners(candidates: List<Candidate>, ref: NodeRef, expectedPackage: String): OwnerResolution {
        val owners = ArrayList<AccessibilityNodeInfo>()
        val members = ArrayList<MutableList<Int>>()
        val ownerIndexOf = arrayOfNulls<Int>(candidates.size)
        var orphaned = 0
        var stale = 0
        val staleOwners = ArrayList<AccessibilityNodeInfo>()
        candidates.forEachIndexed { i, c ->
            // The owner is what gets tapped, so it must itself belong to the scoped package — an
            // embedded foreign-package subtree never lends a tap target (#1149).
            val owner = AccNodeUtils.resolveActionOwner(c.node)
                ?.takeIf { it.packageName?.toString() == expectedPackage }
            if (owner == null || staleOwners.any { it == owner }) { orphaned++; return@forEachIndexed }
            val j = owners.indexOfFirst { it == owner }
            if (j >= 0) {
                members[j].add(i); ownerIndexOf[i] = j
            } else {
                // #1149 review I1: refresh each distinct owner ONCE, up front, so every scan and
                // verification below reads its CURRENT state — a recycled row that rebinds
                // (Decline → Accept) is verified as what it now is. A failed refresh means the view
                // is gone: the owner is dropped with the orphans. The click follows verification
                // with no second refresh, so nothing un-verified can reach dispatch.
                if (!owner.refresh()) { staleOwners.add(owner); orphaned++; stale++; return@forEachIndexed }
                owners.add(owner); members.add(mutableListOf(i)); ownerIndexOf[i] = owners.size - 1
            }
        }
        val refText = ref.text?.takeIf { it.isNotBlank() }
        val targets = owners.mapIndexed { j, owner ->
            val group = members[j].map { candidates[it] }
            val evidence = group.firstOrNull { refText != null && it.node.text?.toString()?.take(50) == refText }
                ?: group.firstOrNull { it.boundsDerived && !it.relaxed }
                ?: group.first()
            OwnedTarget(
                index = j,
                owner = owner,
                evidence = evidence.node,
                inActiveWindow = group.first().inActiveWindow,
                boundsDerived = group.any { it.boundsDerived },
                relaxed = group.all { it.relaxed },
                semantic = group.any { it.semantic },
                ancestors = members[j].flatMap { candidates[it].ancestors }
                    .mapNotNull { ownerIndexOf[it] }.filter { it != j }.toSet(),
            )
        }
        return OwnerResolution(targets, orphaned, stale)
    }

    /**
     * Search the scoped roots, strongest strategy first (so a weak bounds
     * match in one window can't beat a viewId match in another), tagging each
     * hit with whether its source window is the active one ([activeRoot], `==`
     * by [AccessibilityNodeInfo.equals]). The target may live in a window other
     * than the active one — e.g. DoorDash's offer while the bubble holds focus —
     * so candidates from every scoped root are collected; the caller's
     * active-window scoping (#788) prefers the active window's, if any.
     */
    private class CandidateSearch(val candidates: List<Candidate>, val semanticTruncated: Boolean = false)

    private fun findCandidates(
        roots: List<AccessibilityNodeInfo>,
        activeRoot: AccessibilityNodeInfo?,
        ref: NodeRef,
        expectedPackage: String,
    ): CandidateSearch {
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
        // Strategy 2b (#1149): labels are identity, geometry is evidence. A hinted bind is
        // re-found by its EXACT subtree-label fingerprint on the CURRENT screen BEFORE the bounds
        // walk, so a sheet that slid after the bind was captured (#1102) no longer lets frozen
        // bounds decide which node resolves. Bounds stay ranking evidence (ClickCandidateRanker's
        // overlap tier). A walk cut by its depth/fetch bound aborts the whole resolution.
        // #1149 review J3: only a ref with a PROVABLE exact fingerprint enters 2b (NodeRef.hasExactFingerprint);
        // an unprovable one goes straight to strategy 3's containment check, the pre-#1149 shape.
        if (candidates.isEmpty() && ref.hasExactFingerprint) {
            // #1149 review I6: truncation is PER WINDOW. An incomplete window contributes no
            // candidates. The tap aborts only when some window was incomplete AND the active window
            // produced no complete survivor — a background window that overflows its budget must
            // not veto an exact, complete hit on the active sheet. When any window was incomplete,
            // only the active window's hits are kept (an incomplete window may hide a twin of a
            // background survivor). No window incomplete and no hit → strategy 3 runs as before.
            var incompleteWindows = 0
            val semantic = mutableListOf<Candidate>()
            for (root in roots) {
                val found = findNodeBySemantics(root, ref, expectedPackage)
                if (found == null) { incompleteWindows++; continue }
                val inActive = activeRoot != null && root == activeRoot
                val base = semantic.size
                for (hit in found) semantic.add(
                    Candidate(hit.node, inActive, semantic = true, ancestors = hit.ancestors.map { it + base }),
                )
            }
            if (incompleteWindows > 0) {
                val active = semantic.withIndex().filter { it.value.inActiveWindow }
                if (active.isEmpty()) return CandidateSearch(emptyList(), semanticTruncated = true)
                val remap = active.withIndex().associate { (newIdx, old) -> old.index to newIdx }
                active.forEach { (_, c) -> candidates.add(c.copy(ancestors = c.ancestors.mapNotNull { remap[it] })) }
                Timber.tag("Effects").d(
                    "Semantic re-find: %d incomplete window(s) ignored, %d active-window survivor(s) kept",
                    incompleteWindows, candidates.size,
                )
            } else {
                candidates.addAll(semantic)
            }
        }
        // Strategy 3: walk each tree matching by bounds + className. A zero-area ref rect (a
        // row captured mid-inflation at zero height) carries no bounds evidence at all, so the
        // walk is skipped and the tap fails closed to manual (#1093).
        val b = ref.boundsInScreen
        val degenerate = b.right <= b.left || b.bottom <= b.top
        if (candidates.isEmpty() && !degenerate) {
            for (root in roots) {
                val found = mutableListOf<WalkHit>()
                findNodeByBounds(root, ref.boundsInScreen, ref.classNameHint, found, ArrayList())
                val inActive = activeRoot != null && root == activeRoot
                val base = candidates.size
                for (hit in found) candidates.add(
                    Candidate(hit.node, inActive, boundsDerived = true, relaxed = hit.relaxed, ancestors = hit.ancestors.map { it + base }),
                )
            }
        }
        return CandidateSearch(candidates)
    }

    /**
     * A bounded, package-scoped label scan (#1149). [complete] = no in-horizon label went unseen: no
     * fetch refused by the [LABEL_SCAN_NODES] cap and no null child (review I4a). The
     * [LABEL_SCAN_DEPTH] cut is the HORIZON, not incompleteness (vet decision on I2 × I4b): both
     * sides define the fingerprint as the owner's labels within that depth, excluding clickable
     * descendants, so deeper nodes leave it fully determined. Only a complete scan can prove a label
     * fingerprint EXACT. Verification of a
     * label EXPECTATION does not need completeness (review I4c): a found label suffices, and since
     * I3 a collected label can never come from a nested control. [fetched] counts child fetch attempts, nulls included.
     * [exhausted] = the fetch cap (not the depth) cut it.
     */
    private class LabelScan(val labels: List<String>, val complete: Boolean, val exhausted: Boolean, val fetched: Int)

    /**
     * Collect the node's own text/contentDescription plus its bounded subtree's — platform buttons
     * typically carry their label on a child TextView (e.g. DoorDash's
     * `textView_prism_button_title`). The subtree stops at every clickable descendant: those labels
     * are that control's, not this node's (#1149 review I3 — the rule that replaced the
     * compound-owner refusal). Every child fetch is a binder IPC, so it is budgeted BEFORE
     * the call and a null child still spends budget; a child belonging to another package is not
     * read — an embedded foreign subtree must never lend a same-package container its labels
     * (#1102 review constraints 3 and 4, applied in discovery AND verification).
     */
    private fun scanLabels(node: AccessibilityNodeInfo, expectedPackage: String, fetchCap: Int = LABEL_SCAN_NODES): LabelScan {
        val labels = mutableListOf<String>()
        var fetched = 0
        var complete = true
        var exhausted = false
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { labels.add(it) }
            val count = n.childCount
            if (count <= 0) return
            if (depth >= LABEL_SCAN_DEPTH) return // the shared horizon, not a cut (I2 × I4b vet)
            for (i in 0 until count) {
                if (fetched >= fetchCap) { complete = false; exhausted = true; return }
                fetched++
                // #1149 review I4a: an advertised child that cannot be read is UNPROVEN — the scan is
                // incomplete (it still spent a fetch, #1102 constraint 3).
                val child = n.getChild(i) ?: run { complete = false; null } ?: continue
                if (child.packageName?.toString() != expectedPackage) continue
                // #1149 review I3: a clickable descendant is its OWN control — its labels belong to
                // it, never to the container scanned here. A footer can therefore never borrow its
                // Decline button's "Decline" and pass the expectation as if it were the button.
                if (AccNodeUtils.isActionClickable(child)) continue
                visit(child, depth + 1)
                if (exhausted) return
            }
        }
        visit(node, 0)
        return LabelScan(labels, complete, exhausted, fetched)
    }

    private fun collectLabels(node: AccessibilityNodeInfo, expectedPackage: String): List<String> =
        scanLabels(node, expectedPackage).labels

    /** A walk hit: [ancestors] are the indices (into the SAME `out` list) of hits this one is inside. */
    private data class WalkHit(val node: AccessibilityNodeInfo, val relaxed: Boolean, val ancestors: List<Int>)

    /**
     * The part of a node's subtree its OWN label scan would read, relative to the node: labels at
     * relative depth <= [LABEL_SCAN_DEPTH], and `slots[d]` = child slots of region nodes at relative
     * depth d (each one fetch in [scanLabels]). Clickable and foreign-package children spend a slot
     * but contribute nothing (#1149 review I3 / constraint 4).
     */
    private class LabelRegion {
        val labels = ArrayList<Pair<Int, String>>()
        val slots = IntArray(LABEL_SCAN_DEPTH + 1)

        fun absorb(child: LabelRegion) {
            for ((d, label) in child.labels) if (d + 1 <= LABEL_SCAN_DEPTH) labels.add(d + 1 to label)
            for (d in 0 until LABEL_SCAN_DEPTH) slots[d + 1] += child.slots[d]
        }

        /**
         * What [scanLabels] would call complete: <= LABEL_SCAN_NODES in-horizon fetches. Nodes below
         * LABEL_SCAN_DEPTH are outside the fingerprint on BOTH sides (the horizon), so they never
         * make it incomplete; a null child already aborted the walk (I4).
         */
        fun complete(): Boolean = slots.take(LABEL_SCAN_DEPTH).sum() <= LABEL_SCAN_NODES
    }

    private class SemanticHit(val node: AccessibilityNodeInfo, val pre: Int, val lastPre: Int)

    /**
     * Strategy 2b (#1149): every same-package node of [root] that takes a click
     * ([AccNodeUtils.isActionClickable]), matches the ref's class hint (when it has one) and whose
     * COMPLETE label region is the ref's EXACT fingerprint ([NodeRef.fingerprintMatches] — no
     * superset, #1102 review constraint 1). No geometric entrance test. Hits record their nesting
     * (pre-order intervals), so a wrapper carrying its own copy of the labels around the row stays
     * visible to the caller's nested-abort rule.
     *
     * ONE pass (review I7): each node's label region is derived post-order from the children the
     * walk already fetched — the same horizon, ownership and completeness [scanLabels] applies —
     * so every child is fetched once and the budget counts real IPC once.
     *
     * Bounded (#1102 review constraints 2 + 3): at most [TreeLimits.MAX_TREE_DEPTH] deep and
     * [TreeLimits.MAX_TREE_NODES] child fetches per root, budgeted before the call, nulls included.
     * Returns null when the window's search is INCOMPLETE — a bound cut the walk, a child read
     * null, or a candidate's own region is incomplete (review I4b) — because a partial search can
     * leave one wrong survivor.
     */
    private fun findNodeBySemantics(root: AccessibilityNodeInfo, ref: NodeRef, expectedPackage: String): List<WalkHit>? {
        var fetched = 0
        var preCounter = 0
        var truncated = false
        val hits = ArrayList<SemanticHit>()
        fun visit(node: AccessibilityNodeInfo, depth: Int): LabelRegion? {
            val pre = preCounter++
            val region = LabelRegion()
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { region.labels.add(0 to it) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { region.labels.add(0 to it) }
            val count = node.childCount.coerceAtLeast(0)
            region.slots[0] = count
            if (count > 0 && depth >= TreeLimits.MAX_TREE_DEPTH) { truncated = true; return null }
            for (i in 0 until count) {
                if (fetched >= TreeLimits.MAX_TREE_NODES) { truncated = true; return null }
                fetched++
                // An unreadable child may hide the real control (or its twin): incomplete (I4).
                val child = node.getChild(i) ?: run { truncated = true; return null }
                if (child.packageName?.toString() != expectedPackage) continue
                val childRegion = visit(child, depth + 1) ?: return null
                if (!AccNodeUtils.isActionClickable(child)) region.absorb(childRegion)
            }
            val classOk = ref.classNameHint == null || node.className?.toString() == ref.classNameHint
            if (classOk && AccNodeUtils.isActionClickable(node)) {
                val labels = region.labels.map { it.second }
                if (!region.complete()) {
                    // I4b, refined by review J4: an incomplete candidate (fetch-budget cut) vetoes the
                    // window ONLY if what IS visible is still consistent with the fingerprint (visible
                    // hint set ⊆ the ref's) — then the unseen part could complete it into the real
                    // control or its twin. A region already carrying a label OUTSIDE the set can never
                    // be an exact match whatever is unseen, so a big unrelated card does not veto.
                    // A depth cut is the horizon, not incompleteness; a null child aborted above.
                    val visible = labels.mapNotNull(NodeRef::hintHash).toHashSet()
                    if (ref.labelHintHashes.containsAll(visible)) { truncated = true; return null }
                } else if (ref.fingerprintMatches(labels)) {
                    hits.add(SemanticHit(node, pre, preCounter - 1))
                }
            }
            return region
        }
        visit(root, 0)
        if (truncated) return null
        hits.sortBy { it.pre }
        return hits.mapIndexed { i, h ->
            WalkHit(h.node, relaxed = false, ancestors = hits.indices.filter { j ->
                j != i && hits[j].pre < h.pre && h.pre <= hits[j].lastPre
            })
        }
    }

    /**
     * Strategy 3 (#1093 shape — review rounds 2 and 3). Every hit is bounds-derived and must still
     * carry the bind's label hints; the walk itself decides NOTHING about identity:
     *  - an EXACT class+rect match that is CLICKABLE is a hit, and the walk STILL descends — a
     *    clickable wrapper at the captured rect with the real row inside it must expose both, so
     *    the verification stage can see the nesting and abort (pruning here handed the tap to
     *    the wrapper);
     *  - an exact match that is NOT clickable is skipped and descended — the caller resolves its
     *    action owner (#1149), the nearest clickable ANCESTOR, so ranking a shell above its
     *    clickable child would tap something outside the row;
     *  - a clickable same-class node overlapping the rect by >= [RELAXED_BOUNDS_IOU] is a RELAXED
     *    hit, descended into. Nothing is dropped here: a descendant hit that later FAILS
     *    verification must not evict the row it sits in, and one that PASSES makes the pair
     *    undecidable — both are the verification stage's call, which is why each hit records the
     *    hits it is nested inside.
     * Runs only when strategy 2b found nothing (#1149).
     */
    private fun findNodeByBounds(
        node: AccessibilityNodeInfo,
        targetBounds: BoundingBox,
        className: String?,
        out: MutableList<WalkHit>,
        path: ArrayList<Int>,
    ) {
        val liveBounds = Rect()
        node.getBoundsInScreen(liveBounds)
        val live = liveBounds.toBoundingBox()
        val classOk = className == null || node.className?.toString() == className
        // #1149 review J7: the same clickability predicate as everywhere else — a Compose control that
        // only ADVERTISES ACTION_CLICK at the exact rect is otherwise invisible to the bounds walk.
        val hit = classOk && AccNodeUtils.isActionClickable(node) && (
            live == targetBounds || ClickCandidateRanker.boundsIoU(live, targetBounds) >= RELAXED_BOUNDS_IOU
        )
        if (hit) {
            out.add(WalkHit(node, relaxed = live != targetBounds, ancestors = path.toList()))
            path.add(out.size - 1)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodeByBounds(child, targetBounds, className, out, path)
        }
        if (hit) path.removeAt(path.size - 1)
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
