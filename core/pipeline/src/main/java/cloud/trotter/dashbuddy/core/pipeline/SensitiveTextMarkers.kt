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
        // DasherDirect "Transfer out" balance screen (#794) — leaked the plaintext
        // balance to two UNKNOWN captures on the 2026-07-17 dash (copy "Transfer out"
        // / "$X.XX available" is missed by "Transfer to bank" and "Available Balance").
        // The rule-side block is the sensitive.transfer_out branch; this marker is the
        // fail-closed backstop and the shareable-log scrub anchor. "Transfer out" appears
        // on no recognized customer-facing surface in the corpus (verified per the #738
        // uniqueness discipline), so it cannot over-block a delivery screen.
        "Transfer out",
        // The DasherDirect transfer surface's OTHER direction (#884): the "Transfer in"
        // heading of the deposit-to-DasherDirect flow. The rule side already blocks it
        // (the `sensitive.savings` branch's all["Transfer in", "available"] alternative),
        // but the keyword was missing, so a "Transfer in" CLICK — whose envelope serializes
        // the tapped node in ISOLATION, with no co-present "available" balance line for the
        // AND-pair rule to fire on — had no backstop and reached disk on the 2026-07-24 build.
        // Like "Transfer out", the phrase appears on no recognized customer-facing surface in
        // the corpus (#738 uniqueness discipline), so it cannot over-block a delivery screen.
        "Transfer in",
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
        // uber.screen.sensitive.* (#762 D10) — Uber's sensitive rules (matchers/rules/uber.json5)
        // anchor on these strings, which had NO overlapping keyword above (case-insensitive
        // substring checked): the wallet balance card ("Uber Pro Card"), the cashout destination
        // screen ("Transfer to bank"), and the tax-document identity screen ("Tax information").
        // The rules were DoorDash-seeded, so an Uber banking screen whose only sensitive wording
        // was one of these phrases (no co-occurring "Instant Pay"/"Cash out"/"Social Security")
        // was invisible to every scrub layer this list backs: the UNKNOWN-capture scan when the
        // uber ruleset fails to load or misses a variant, and the shareable-log scrub sink
        // (#551), where rules never apply at all. "Card number" closes the same gap for the
        // low-confidence `uber.screen.sensitive.catchall` net (its `allTextContainsAny` list
        // carries "card number" verbatim, previously its one entry with no keyword overlap).
        // Drift-guarded by SensitiveMarkerAssetCoverageTest (asset-derived, all platforms).
        "Uber Pro Card",
        "Transfer to bank",
        "Tax information",
        "Card number",
        // Uber's selfie identity-verification CAMERA (#861) — the biometric-image capture
        // surface the `sensitive.id_verification` branch of `uber.screen.sensitive.known`
        // blocks. Its primary rule anchor is the platform's own face-camera component view
        // ids (structurally invisible to a text backstop), so these are the flow's two
        // text-bearing frames — the guide prompt and the success confirmation — which no
        // keyword above overlapped. Same fail-closed reasoning as the group above: the
        // marker list must still drop the frame when the ruleset misses a variant or fails
        // to load. Drift-guarded by SensitiveMarkerAssetCoverageTest.
        "Fit your face in the guide",
        "Thanks for verifying",
    )

    /**
     * Shaped-value patterns no keyword list can cover: SSNs, card PANs, and the
     * amount-bearing DasherDirect transfer button.
     *
     * Matched against the NORMALIZED text (see [normalize]), so every pattern is written in
     * lowercase with ASCII spaces — normalization already folded case, homoglyph whitespace,
     * fullwidth digits/`＄`, and zero-width injections before the scan runs.
     *
     * Conservative shapes to avoid flagging ordinary money/IDs, and deliberately BOUNDED
     * (no nested/unbounded quantifiers) so a hostile third-party string cannot make the scan
     * backtrack: each is a linear scan with fixed-count repetitions only.
     *
     * `internal` (#862) so `MarkerLogIdTest` pins the LIVE patterns rather than a hand-copied
     * list — a shape added here is covered by the log-safety guard automatically.
     */
    internal val SHAPE_PATTERNS: List<Regex> = listOf(
        // SSN: 123-45-6789
        Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
        // Card PAN: 4 groups of 4 separated by space/dash (16 digits)
        Regex("""\b\d{4}[ -]\d{4}[ -]\d{4}[ -]\d{4}\b"""),
        // DasherDirect transfer CONFIRM button (#884): the label interpolates the dasher's own
        // balance — "Transfer $45.66" / "Transfer $83.65" / "Transfer $68.52" all reached disk
        // as UNKNOWN CLICK envelopes on the 2026-07-24 build. No keyword can own this: the
        // amount is the variable part, and a bare "Transfer" keyword would be far too broad.
        // The shape is the amount ADJACENCY — "transfer", optional spaces, "$", a digit — which
        // is banking vocabulary wherever it appears, so a false hit costs at most one debug
        // capture (fail toward privacy). The bounded ` {0,4}` gaps absorb the per-character
        // space normalization (a tab/NBSP run normalizes to several ASCII spaces) and let the
        // sibling-split form ("Transfer" | "$45.66") rejoin across the allText join.
        //
        // Sibling shapes deliberately NOT added: "Cash out $<amt>" needs nothing — the bare
        // "Cash out" keyword above already substring-matches it. "Deposit $<amt>" is not
        // fielded (0 hits across all twelve 2026-07 pulls; the only "deposit" text on the
        // surface is the benign "deposited to your DoorDash …" earnings copy, which carries
        // no adjacent "$"), so it stays out per the #738 uniqueness discipline — a marker is
        // evidence-driven, not speculative. Revisit if a pull ever shows it.
        Regex("""transfer {0,4}\$ {0,4}\d"""),
    )

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
        scan(normalize(tree.allScrubbableText().joinToString(" ")))
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
