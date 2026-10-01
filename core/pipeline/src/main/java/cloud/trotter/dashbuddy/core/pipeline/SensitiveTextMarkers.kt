package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.census.contract.SensitiveMarkerData
import cloud.trotter.census.contract.TextFold
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import java.util.Locale

/**
 * App-owned fail-closed backstop for the sensitive-screen pledge (#432).
 * Data owner: `census-contract`'s [SensitiveMarkerData] (#1173).
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

    /**
     * Sentinel returned when [normalize] throws — the scan is FAIL-CLOSED (#590):
     * a text we cannot normalize is treated as toxic, never silently reported
     * clean. Losing a real banking screen to disk because a homoglyph tripped the
     * normalizer would defeat the whole backstop, so an unexpected throw drops the
     * capture exactly as a real marker would.
     *
     * `internal` (#862) so `MarkerLogIdTest` pins the live sentinel, not a copy of it.
     */
    internal const val NORMALIZE_FAILED = "normalize-error"

    /**
     * Markers pre-normalized once (evasion resistance, #590). The scan compares
     * NORMALIZED text against NORMALIZED markers so the two sides agree — a marker
     * with an ASCII space matches text whose space arrived as an NBSP after
     * folding. Paired with the original marker so [findMarker] still returns the
     * human-readable form for the WARN log.
     */
    private val normalizedKeywords: List<Pair<String, String>> by lazy {
        KEYWORDS.map { normalize(it) to it }
    }

    /**
     * The fully STRIPPED normal form (#1160 reviews RR1, WW1): EXACTLY the boundary-preserving form
     * ([normalizePreserving], the pre-#1160 normalizer) with its supplementary-plane FORMAT code points
     * removed — "old normalizer + strip", no further NFKC. (A second NFKC pass — the census fold — composed
     * `Vis<tag>a<ZWJ><U+0301>` into `visá` and lost the `visa` keyword both forms must see.) The census
     * canonical fold (`TextFold.foldForCensus`) is the builder's own and is NOT a sensitive-scan form. The
     * KEYWORDS are normalized with this form; being ASCII, they read the same in both forms.
     */
    internal fun normalize(s: String): String = TextFold.stripSupplementaryFormat(normalizePreserving(s))

    /**
     * The BOUNDARY-PRESERVING normal form — byte-for-byte the pre-#1160 normalizer (#590; supplementary-plane
     * FORMAT chars kept), a single allocation pass over the NFKC output. It closes the evasion classes a
     * plain `contains` substring test misses:
     *  - NFKC folds NBSP (U+00A0) / narrow-NBSP (U+202F) → space and fullwidth digits/letters (U+FF10+) /
     *    ideographic space (U+3000) → ASCII;
     *  - BMP zero-width & other format chars (U+200B/200C/200D/FEFF, `Character.FORMAT`) are stripped, so a
     *    marker split by an invisible char rejoins;
     *  - unicode dashes (U+2010–U+2015, U+2212 minus) fold to ASCII `-` so the SSN/PAN shapes match
     *    homoglyph hyphens;
     *  - whitespace and lowercase: [spaceAndLower].
     * Review RR1: [findMarker] scans the SCANNED TEXT in this form AND in the fully stripped [normalize] and
     * drops on EITHER hit — stripping a supplementary FORMAT char can erase the `\b` a shape pattern needs,
     * while keeping it lets a tag char split a keyword; the union covers both (fail toward privacy).
     */
    internal fun normalizePreserving(s: String): String = spaceAndLower(TextFold.foldGlyphsPreservingSupplementary(s))

    /** [normalizePreserving] plus the fold's own supplementary-FORMAT flag (review AF6). */
    private fun normalizePreservingFlagged(s: String): TextFold.Folded {
        val folded = TextFold.foldGlyphsPreservingSupplementaryFlagged(s)
        return TextFold.Folded(spaceAndLower(folded.text), folded.hasSupplementaryFormat)
    }

    /**
     * Every remaining whitespace char (incl. the U+001F unit separator used to join sibling text) becomes
     * one ASCII space, and `Locale.ROOT` lowercase gives locale-safe case-insensitivity in one place (so the
     * scan can use a plain, allocation-light `contains`).
     */
    private fun spaceAndLower(folded: String): String {
        val sb = StringBuilder(folded.length)
        for (ch in folded) {
            if (ch == '\u001F' || ch.isWhitespace()) sb.append(' ') else sb.append(ch)
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    /**
     * Scan both normal forms; the first hit wins (review RR1). Since the stripped form is the preserving
     * form minus its supplementary-plane FORMAT code points (review WW1), the two strings are IDENTICAL
     * unless such a code point is present — so skipping the second scan when there is none is exact, not
     * an approximation (reviews UU2, WW3). The LogScrubber and CaptureWriter hot paths scan once. Review
     * AB6: the stripped form is derived from the ALREADY-computed preserving form — the identity
     * [normalize] is defined by — never re-normalized from the raw text.
     */
    private fun scanBothForms(text: String): String? {
        // AF6: the flag comes from the fold's own pass (lowercasing cannot add or remove a FORMAT code point).
        val preserving = normalizePreservingFlagged(text)
        return scan(preserving.text)
            ?: if (preserving.hasSupplementaryFormat) scan(TextFold.stripSupplementaryFormat(preserving.text)) else null
    }

    /**
     * The marker name reported for a shaped-value hit. `internal` + factored out (#862) so the
     * log-safety guard derives the same names production does instead of re-spelling the format.
     */
    internal fun shapeMarkerName(pattern: Regex): String = "shape:${pattern.pattern}"

    private fun scan(normalizedText: String): String? {
        val keyword = normalizedKeywords.firstOrNull { (norm, _) -> normalizedText.contains(norm) }
        if (keyword != null) return keyword.second
        return SHAPE_PATTERNS.firstOrNull { it.containsMatchIn(normalizedText) }
            ?.let { shapeMarkerName(it) }
    }

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
        scanBothForms(tree.allScrubbableText().joinToString(" "))
    } catch (_: Throwable) {
        NORMALIZE_FAILED
    }

    /**
     * Scan a flat text blob (notification body) for a sensitive marker.
     * FAIL-CLOSED: a normalization throw returns the toxic sentinel, never null.
     */
    fun findMarker(text: String): String? = try {
        scanBothForms(text)
    } catch (_: Throwable) {
        NORMALIZE_FAILED
    }
}
