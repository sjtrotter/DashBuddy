package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.action.TargetExpectation
import cloud.trotter.dashbuddy.domain.model.accessibility.BoundingBox
import cloud.trotter.dashbuddy.domain.pipeline.LabelHorizon
import cloud.trotter.dashbuddy.domain.pipeline.LabelNode
import cloud.trotter.dashbuddy.domain.pipeline.LabelScan
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
 *    [AccessibilitySource.LiveRoots.active] (one enumeration, #1149 N3); when any label-verified candidate
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
        // #1149 review N3: the roots AND the active root come from ONE enumeration; each read records
        // the active root of the very enumeration its roots came from.
        var activeRoot: AccessibilityNodeInfo? = null
        val rootsSource = {
            val live = accessibilitySource.getLiveWindowRoots()
            activeRoot = live.active
            live.roots.filter { it.packageName?.toString() == expectedPackage }
        }
        val roots = if (allowRetry) awaitLiveRoots(expectedPackage, source = rootsSource) else rootsSource()
        if (roots.isEmpty()) {
            Timber.tag("Effects").w(
                "No live windows for package %s after %d retries over %dms — cannot click (%s)",
                expectedPackage, RETRY_DELAYS_MS.size, RETRY_DELAYS_MS.sum(), description,
            )
            return false
        }

        // #788: the active (topmost) window's root, used to scope candidates below — from the SAME
        // enumeration as `roots` (N3), so a root in `roots` that `==` it (AccessibilityNodeInfo.equals
        // = windowId+sourceNodeId) IS the active window. When the active window belongs to another
        // package (e.g. the dasher's bubble holds focus), it was package-filtered out of `roots`, so it
        // matches nothing and scoping no-ops (we fall through to all windows, as before).
        val search = findCandidates(roots, activeRoot, ref, expectedPackage)
        if (search.inconclusiveHits > 0) {
            // #1149 review L1 (decideSemanticOutcome): 2b found hit(s) but a window of the deciding set
            // could not be read completely — a hidden twin is possible, and the bounds walk must not
            // guess past it (#1102 review constraint 2).
            warnInconclusive(description, search.inconclusiveHits)
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
        if (owned.staleSemantic > 0) {
            warnInconclusive(description, candidates.count { it.semantic })
            return false
        }
        if (owned.targets.isEmpty()) return false

        // Label-verify once, on the OWNER's bounded subtree, and keep each surviving owner's labels
        // alongside it — the ranker below wants them too (for WARN diagnostics). For a 2b hit this
        // scan is a DELIBERATE second read (review J9): discovery read the pre-refresh tree, and
        // verification must run on the REFRESHED owner (I1) — it is not a redundant fetch.
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
            // always count — the owner may sit more than NodeRef.LABEL_SCAN_DEPTH levels (or NodeRef.LABEL_SCAN_NODES
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
            // J6 under L1: an unprovable 2b hit in the deciding set is |H| >= 1 ∧ I.
            warnInconclusive(description, owned.targets.count { it.semantic })
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

    private class OwnerResolution(val targets: List<OwnedTarget>, val orphaned: Int, val stale: Int, val staleSemantic: Int)

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
        var staleSemantic = 0
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
                if (!owner.refresh()) {
                    staleOwners.add(owner); orphaned++; stale++
                    // #1149 review L5: a 2b hit that cannot be refreshed is UNPROVABLE, not absent —
                    // dropping it would hand its twin the tap. Every semantic candidate here is from the
                    // deciding set (decideSemanticOutcome), so the caller aborts.
                    if (c.semantic) staleSemantic++
                    return@forEachIndexed
                }
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
        return OwnerResolution(targets, orphaned, stale, staleSemantic)
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
    /** [inconclusiveHits] > 0: 2b found that many hits in a deciding set that was incomplete — abort (#1149 L1). */
    private class CandidateSearch(val candidates: List<Candidate>, val inconclusiveHits: Int = 0)

    private class SemanticWindow(val result: SemanticSearch, val inActive: Boolean)

    private sealed interface SemanticOutcome {
        data class Use(val candidates: List<Candidate>) : SemanticOutcome
        data class Inconclusive(val hits: Int) : SemanticOutcome
        data class FallThrough(val incompleteWindows: Int) : SemanticOutcome
    }

    /**
     * #1149 review L1/N1 — THE 2b outcome rule, one owner, over the DECIDING set: the active platform
     * window's search when it produced ≥ 1 hit; otherwise EVERY scoped window's search (with all their
     * incompleteness) — the #788 rule "active contributes none → keep them all" (a small same-package
     * dialog active over the still-sliding receipt sheet must not hand the tap to frozen bounds).
     * With H = the deciding set's hits and I = "a window in it was incomplete" (walk cut, unreadable
     * child, or a vetoing region):
     *  - |H| = 0 → FALL THROUGH to strategy 3, whether or not I: nothing was found, so nothing can be
     *    wrong, and strategy 3 keeps its own gates (containment, nested abort, ranker, #788);
     *  - |H| ≥ 1 ∧ I → INCONCLUSIVE, abort: a hidden twin is possible;
     *  - otherwise USE H (≥ 2 of them are twins: stored-text tie-break or abort, downstream).
     */
    private fun decideSemanticOutcome(deciding: List<SemanticWindow>): SemanticOutcome {
        val hits = deciding.sumOf { it.result.hits.size }
        if (hits == 0) return SemanticOutcome.FallThrough(deciding.count { it.result.incomplete })
        if (deciding.any { it.result.incomplete }) return SemanticOutcome.Inconclusive(hits)
        val out = mutableListOf<Candidate>()
        for (w in deciding) {
            val base = out.size
            for (hit in w.result.hits) out.add(
                Candidate(hit.node, w.inActive, semantic = true, ancestors = hit.ancestors.map { it + base }),
            )
        }
        return SemanticOutcome.Use(out)
    }

    private fun warnInconclusive(description: String, hits: Int) {
        Timber.tag("Effects").w(
            "Semantic re-find for %s inconclusive (%d hit(s), incomplete) — aborting to manual (#1149)",
            description, hits,
        )
    }

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
            // N2: walk the active platform root FIRST; the other roots only when it produced no hit (N1's
            // fallback to every window). Under our bubble (active root not in `roots`) walk them all.
            val activeIdx = if (activeRoot == null) -1 else roots.indexOfFirst { it == activeRoot }
            val activeWindow = if (activeIdx < 0) null else
                SemanticWindow(findNodeBySemantics(roots[activeIdx], ref, expectedPackage), inActive = true)
            val deciding = if (activeWindow != null && activeWindow.result.hits.isNotEmpty()) {
                listOf(activeWindow)
            } else {
                listOfNotNull(activeWindow) + roots.filterIndexed { i, _ -> i != activeIdx }
                    .map { SemanticWindow(findNodeBySemantics(it, ref, expectedPackage), inActive = false) }
            }
            when (val outcome = decideSemanticOutcome(deciding)) {
                is SemanticOutcome.Use -> candidates.addAll(outcome.candidates)
                is SemanticOutcome.Inconclusive -> return CandidateSearch(emptyList(), inconclusiveHits = outcome.hits)
                is SemanticOutcome.FallThrough -> Timber.tag("Effects").d(
                    "Semantic re-find found nothing (%d incomplete window(s) in the deciding set) — strategy 3",
                    outcome.incompleteWindows,
                )
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

    /** A walk hit: [ancestors] are the indices (into the SAME `out` list) of hits this one is inside. */
    private data class WalkHit(val node: AccessibilityNodeInfo, val relaxed: Boolean, val ancestors: List<Int>)

    /**
     * #1149 review N8 — the POST-REFRESH verification scan: the one [LabelHorizon] rule over the live
     * owner. Every child fetch is a binder IPC, so [LiveLabelNode] fetches on `get(i)` and the horizon
     * budgets each slot BEFORE touching it (a null child still spends one); another package's child is
     * never read (#1102 review constraints 3 and 4). For a 2b hit this is a DELIBERATE second read
     * (review J9): discovery read the pre-refresh tree, verification must read the refreshed owner (I1).
     * Verification of a label EXPECTATION does not need [LabelScan.complete] (I4c): a found label
     * suffices, and since I3 a collected label can never come from a nested control.
     */
    private fun scanLabels(node: AccessibilityNodeInfo, expectedPackage: String): LabelScan =
        LabelHorizon.scan(LiveLabelNode(node, expectedPackage))

    /** A live node as a [LabelNode]: children are fetched lazily, one `getChild` per slot the horizon touches. */
    private class LiveLabelNode(private val node: AccessibilityNodeInfo, private val expectedPackage: String) : LabelNode {
        override val ownLabels: List<String> = ownLabelsOf(node)
        override val takesClick: Boolean get() = AccNodeUtils.isActionClickable(node)
        override val foreign: Boolean get() = node.packageName?.toString() != expectedPackage
        override val unreadableChildren: Int get() = 0
        override fun children(): List<LabelNode?> = object : AbstractList<LabelNode?>() {
            override val size: Int = node.childCount.coerceAtLeast(0)
            override fun get(index: Int): LabelNode? = node.getChild(index)?.let { LiveLabelNode(it, expectedPackage) }
        }
    }

    /**
     * A node the 2b walk already fetched, as a [LabelNode] (review I7 + N8): its [slots] hold the children
     * the walk read; a slot the walk did not (or could not) read is null — unreadable to the horizon, so
     * scanning a candidate never issues a second fetch.
     */
    private class WalkNode(
        node: AccessibilityNodeInfo,
        override val foreign: Boolean,
        override val takesClick: Boolean,
        val slots: Array<WalkNode?>,
    ) : LabelNode {
        override val ownLabels: List<String> = ownLabelsOf(node)
        override val unreadableChildren: Int get() = 0
        override fun children(): List<LabelNode?> = slots.asList()
    }

    /** One window's 2b result: its hits, and whether anything in it could not be read completely (#1149 L1). */
    private class SemanticSearch(val hits: List<WalkHit>, val incomplete: Boolean)

    private class SemanticHit(val node: AccessibilityNodeInfo, val pre: Int, val lastPre: Int)

    /**
     * Strategy 2b (#1149): every same-package node of [root] that takes a click
     * ([AccNodeUtils.isActionClickable]), matches the bind's owner class (when it has one) and whose
     * COMPLETE label horizon is the ref's EXACT fingerprint ([NodeRef.fingerprintMatches] — no
     * superset, #1102 review constraint 1). No geometric entrance test. Hits record their nesting
     * (pre-order intervals), so a wrapper carrying its own copy of the labels around the row stays
     * visible to the caller's nested-abort rule.
     *
     * ONE pass (review I7): every child is fetched once, into a [WalkNode]; each candidate's horizon is
     * then [LabelHorizon.scan] over those already-fetched nodes (N8 — the same rule as bind time and
     * verification), so the budget counts real IPC once.
     *
     * Bounded (#1102 review constraints 2 + 3): at most [TreeLimits.MAX_TREE_DEPTH] deep and
     * [TreeLimits.MAX_TREE_NODES] child fetches per root, budgeted before the call, nulls included.
     * Returns the hits found AND whether the window is INCOMPLETE — a bound cut the walk, a child read
     * null, or a candidate's own horizon is incomplete with its visible labels still consistent
     * (I4b/J4). What that means for the tap is [decideSemanticOutcome]'s call (L1/N1).
     */
    private fun findNodeBySemantics(root: AccessibilityNodeInfo, ref: NodeRef, expectedPackage: String): SemanticSearch {
        var fetched = 0
        var preCounter = 0
        var incomplete = false
        var stopped = false
        val hits = ArrayList<SemanticHit>()
        // L2/N7: 2b filters on the bind's OWNER class (its fingerprint is the owner's). A null
        // ownerClassHint on a ref that reached 2b (hasExactFingerprint) means the owner HAD no class —
        // no filter; legacy refs never reach 2b (they are never complete), so there is no fallback.
        val ownerClass = ref.ownerClassHint
        fun visit(node: AccessibilityNodeInfo, depth: Int): WalkNode {
            val pre = preCounter++
            val count = node.childCount.coerceAtLeast(0)
            val self = WalkNode(node, foreign = false, takesClick = AccNodeUtils.isActionClickable(node), slots = arrayOfNulls(count))
            if (count > 0 && depth >= TreeLimits.MAX_TREE_DEPTH) {
                incomplete = true // a tree the mapper itself would have cut; the slots stay unreadable
            } else {
                for (i in 0 until count) {
                    if (stopped || fetched >= TreeLimits.MAX_TREE_NODES) { incomplete = true; stopped = true; break }
                    fetched++
                    // An unreadable child may hide the real control (or its twin): incomplete (I4 → L1).
                    val child = node.getChild(i) ?: run { incomplete = true; null } ?: continue
                    if (child.packageName?.toString() != expectedPackage) {
                        self.slots[i] = WalkNode(child, foreign = true, takesClick = false, slots = emptyArray())
                        continue
                    }
                    self.slots[i] = visit(child, depth + 1)
                }
            }
            val classOk = ownerClass == null || node.className?.toString() == ownerClass
            if (classOk && self.takesClick) {
                val scan = LabelHorizon.scan(self)
                if (!scan.complete) {
                    // I4b, refined by J4: an incomplete candidate marks the window incomplete ONLY if what
                    // IS visible is still consistent with the fingerprint (visible hint set ⊆ the ref's) —
                    // the unseen part could complete it into the control or its twin. A region already
                    // carrying a label OUTSIDE the set can never be an exact match, so it does not count.
                    if (ref.visibleConsistentWith(scan.labels)) incomplete = true
                } else if (ref.fingerprintMatches(scan.labels)) {
                    hits.add(SemanticHit(node, pre, preCounter - 1))
                }
            }
            return self
        }
        visit(root, 0)
        hits.sortBy { it.pre }
        return SemanticSearch(hits.mapIndexed { i, h ->
            WalkHit(h.node, relaxed = false, ancestors = hits.indices.filter { j ->
                j != i && hits[j].pre < h.pre && h.pre <= hits[j].lastPre
            })
        }, incomplete)
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

/** A live node's own non-blank text and contentDescription — the [LabelNode.ownLabels] of both fire-time adapters. */
private fun ownLabelsOf(node: AccessibilityNodeInfo): List<String> = listOfNotNull(
    node.text?.toString()?.takeIf { it.isNotBlank() },
    node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
)
