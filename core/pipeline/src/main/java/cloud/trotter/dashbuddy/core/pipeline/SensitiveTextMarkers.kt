package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import java.text.Normalizer
import java.util.Locale

/**
 * App-owned fail-closed backstop for the sensitive-screen pledge (#432).
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

    /**
     * Keywords inlined from the production sensitive screen rules.
     * Case-insensitive substring match.
     */
    val KEYWORDS: List<String> = listOf(
        // doordash.screen.sensitive.known
        "Bank Account",
        "Routing Number",
        "Social Security",
        "Direct Deposit",
        "Visa",
        "Mastercard",
        "ending in",
        "Linked accounts",
        "Statements & documents",
        "Emergency contact details",
        // doordash.screen.sensitive.catchall
        "Crimson",
        "DasherDirect",
        "Biometric",
        "Available Balance",
        "Tax Form",
        "Expiry",
        "Enter the code we sent",
        "t=completed_view",
        // platform-agnostic payout/identity surfaces (#432)
        "Instant Pay",
        "Cash out",
        "CVV",
        // DasherDirect Savings flow (#463) — leaked dollar balances to UNKNOWN
        // capture on the 2026-06-12 dash; the rule-side block is the
        // sensitive.savings branch, these markers are the fail-closed backstop.
        "Savings jar",
        "You transferred",
        // Alcohol-delivery DOCUMENT-capture surfaces only (#463): the license-scan
        // camera (an image of a government ID) and the signature pad/handoff.
        // The ID-CHECK instruction screen and the alcohol arrival card are NOT
        // here — they carry no document image, only customer name/address (which
        // the dropoff parse hashes); we recognize them (alcohol_id_check /
        // dropoff_pre_arrival), we don't block them. We block the *dasher's* own
        // sensitive data, and image captures of IDs/signatures — not customers.
        "Scan barcode on the back",
        "Driver's License",
        "provide their signature",
        "A recipient signature is required",
    )

    /**
     * Shaped-value patterns no keyword list can cover: SSNs and card PANs.
     * Conservative shapes to avoid flagging ordinary money/IDs.
     */
    private val SHAPE_PATTERNS: List<Regex> = listOf(
        // SSN: 123-45-6789
        Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
        // Card PAN: 4 groups of 4 separated by space/dash (16 digits)
        Regex("""\b\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4}\b"""),
    )

    /**
     * Sentinel returned when [normalize] throws — the scan is FAIL-CLOSED (#590):
     * a text we cannot normalize is treated as toxic, never silently reported
     * clean. Losing a real banking screen to disk because a homoglyph tripped the
     * normalizer would defeat the whole backstop, so an unexpected throw drops the
     * capture exactly as a real marker would.
     */
    private const val NORMALIZE_FAILED = "normalize-error"

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
     * Single-pass homoglyph/whitespace normalizer (#590) shared by both scan
     * overloads and applied to BOTH sides (markers + scanned text). Closes the
     * evasion classes a plain `contains` substring test misses:
     *  - NFKC folds NBSP (U+00A0) / narrow-NBSP (U+202F) → space and fullwidth
     *    digits/letters (U+FF10+) / ideographic space (U+3000) → ASCII;
     *  - zero-width & other format chars (U+200B/200C/200D/FEFF, `Character.FORMAT`)
     *    are stripped, so a marker split by an invisible char rejoins;
     *  - unicode dashes (U+2010–U+2015, U+2212 minus) fold to ASCII `-` so the
     *    SSN/PAN shapes match homoglyph hyphens;
     *  - all remaining whitespace (incl. the `` unit separator used to join
     *    sibling text) collapses to a single ASCII space;
     *  - `Locale.ROOT` lowercase gives locale-safe case-insensitivity in one place
     *    (so the scan can use a plain, allocation-light `contains`).
     * Single allocation pass over the NFKC output; NFKC itself is O(n).
     */
    internal fun normalize(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when {
                Character.getType(ch) == Character.FORMAT.toInt() -> {} // strip zero-width / format
                ch in '‐'..'―' || ch == '−' -> sb.append('-') // unicode dashes → hyphen
                ch == '' || ch.isWhitespace() -> sb.append(' ') // canonicalize whitespace
                else -> sb.append(ch)
            }
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    private fun scan(normalizedText: String): String? {
        val keyword = normalizedKeywords.firstOrNull { (norm, _) -> normalizedText.contains(norm) }
        if (keyword != null) return keyword.second
        return SHAPE_PATTERNS.firstOrNull { it.containsMatchIn(normalizedText) }
            ?.let { "shape:${it.pattern}" }
    }

    /**
     * Scan the tree's text for a sensitive marker. Returns the first
     * matched marker (for logging) or null when clean.
     *
     * The whole `allText` is joined on a space and scanned as ONE normalized blob
     * (#590): a keyword within a single node stays intact, AND a keyword split
     * across adjacent sibling nodes ("Bank" | "Account") rejoins across the space.
     * Uses the existing `allText` walk — no new tree traversal. FAIL-CLOSED: any
     * throw in normalization returns the toxic sentinel (drop the capture), never
     * null.
     */
    fun findMarker(tree: UiNode): String? = try {
        scan(normalize(tree.allText.joinToString(" ")))
    } catch (_: Throwable) {
        NORMALIZE_FAILED
    }

    /**
     * Scan a flat text blob (notification body) for a sensitive marker.
     * FAIL-CLOSED: a normalization throw returns the toxic sentinel, never null.
     */
    fun findMarker(text: String): String? = try {
        scan(normalize(text))
    } catch (_: Throwable) {
        NORMALIZE_FAILED
    }
}
