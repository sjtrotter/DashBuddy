package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

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
        // Customer ID / signature capture during alcohol delivery (#463) — the
        // license-scan, identity-match, and signature-handoff surfaces.
        // Rule-side block is sensitive.id_verification; markers are the backstop.
        // NB: the alcohol delivery instruction CHECKLIST ("Verify recipient's
        // identity" step text) is deliberately NOT here — that's recognized as
        // flow (#462); only the actual capture surfaces are blocked.
        "Scan barcode on the back",
        "Driver's License",
        "Identity verification",
        "provide their signature",
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
     * Scan the tree's text for a sensitive marker. Returns the first
     * matched marker (for logging) or null when clean.
     */
    fun findMarker(tree: UiNode): String? {
        for (text in tree.allText) {
            val keyword = KEYWORDS.firstOrNull { text.contains(it, ignoreCase = true) }
            if (keyword != null) return keyword
            val shape = SHAPE_PATTERNS.firstOrNull { it.containsMatchIn(text) }
            if (shape != null) return "shape:${shape.pattern}"
        }
        return null
    }

    /** Scan a flat text blob (notification body) for a sensitive marker. */
    fun findMarker(text: String): String? {
        val keyword = KEYWORDS.firstOrNull { text.contains(it, ignoreCase = true) }
        if (keyword != null) return keyword
        return SHAPE_PATTERNS.firstOrNull { it.containsMatchIn(text) }
            ?.let { "shape:${it.pattern}" }
    }
}
