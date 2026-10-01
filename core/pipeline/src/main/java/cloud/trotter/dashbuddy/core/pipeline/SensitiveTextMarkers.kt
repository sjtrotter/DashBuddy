package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.census.contract.SensitiveMarkerData
import cloud.trotter.census.contract.SensitiveMarkerScan
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * App-owned fail-closed backstop for the sensitive-screen pledge (#432).
 * Scan + normal form owned by `census-contract`'s [SensitiveMarkerScan] (#1173 review);
 * this object is the UiNode adapter + the app-facing names.
 *
 * The matcher-layer block (priority-0 sensitive rules) fails OPEN for
 * screens no rule recognizes: an unmatched banking screen classifies
 * UNKNOWN and — in debug builds — its full tree text is captured to disk
 * for triage. These markers scan an UNKNOWN frame's text before capture;
 * a hit drops the capture entirely. They are deliberately independent of
 * the (forkable, future-CDN #192) rulesets — a bundle that loses its
 * sensitive rules cannot disable this guard.
 *
 * The keyword list is the SSOT shared with the test-side
 * `SnapshotSecurityScanner` (golden-corpus toxicity guard), so production
 * and test agree on what "toxic" means.
 */
object SensitiveTextMarkers {

    val KEYWORDS: List<String> get() = SensitiveMarkerData.KEYWORDS

    internal val SHAPE_PATTERNS: List<Regex> get() = SensitiveMarkerData.SHAPE_PATTERNS

    internal const val NORMALIZE_FAILED = SensitiveMarkerScan.NORMALIZE_FAILED

    internal fun normalize(s: String): String = SensitiveMarkerScan.normalize(s)

    internal fun normalizePreserving(s: String): String = SensitiveMarkerScan.normalizePreserving(s)

    internal fun shapeMarkerName(pattern: Regex): String = SensitiveMarkerScan.shapeMarkerName(pattern)

    /**
     * Scan the tree's text for a sensitive marker. Returns the first
     * matched marker (for logging) or null when clean.
     *
     * The whole tree's scrubbable text is joined on a space and scanned as ONE
     * normalized blob (#590): a keyword within a single node stays intact, AND a
     * keyword split across adjacent sibling nodes ("Bank" | "Account") rejoins
     * across the space. FAIL-CLOSED: any throw in normalization returns the toxic
     * sentinel (drop the capture), never null.
     *
     * Reads [UiNode.allScrubbableText] — the PRIVACY-side collection — rather
     * than the recognition-side `allText` (#835), so a marker riding a node's
     * `stateDescription` drops the capture too. `allText` deliberately excludes
     * that field because rules match on it; this scan must not.
     */
    fun findMarker(tree: UiNode): String? = try {
        SensitiveMarkerScan.findMarker(tree.allScrubbableText().joinToString(" "))
    } catch (_: Throwable) {
        NORMALIZE_FAILED
    }

    fun findMarker(text: String): String? = SensitiveMarkerScan.findMarker(text)
}
