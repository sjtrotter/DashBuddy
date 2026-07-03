package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.core.pipeline.rules.CompiledRedact
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * App-owned, rules-independent backstop for the CUSTOMER-PII redaction pledge
 * (#624) — the recognized-frame analogue of [SensitiveTextMarkers].
 *
 * The two SSOTs differ in both target and action:
 * - [SensitiveTextMarkers] guards the UNKNOWN path against the DASHER's OWN
 *   sensitive screens (banking/identity) by DROPPING the whole capture.
 * - This SSOT guards RECOGNIZED frames whose rule SHOULD have declared a
 *   `redact` block but didn't, and it SCRUBS only the offending node to
 *   `[redacted]` (the capture still ships, minus the leaked PII).
 *
 * Why it's needed: the #598 `sha256`→`redact` compile gate only fires for a rule
 * that HASHES PII in its parse. A rule that ships raw customer text WITHOUT
 * hashing it (the live `dropoff_reminder` address leak this PR also fixes in
 * data) stays silent — and once rules are downloaded over a CDN (#192/#416/#419)
 * a rule that simply omits redaction would leak. This backstop closes that class.
 *
 * Markers are customer-PII LABEL PREFIXES: a node whose text starts with one
 * carries a customer name/identifier immediately after. `"Heading to "` is
 * deliberately EXCLUDED (VET V2) — it prefixes STORE names on recognized
 * pickup-nav frames, which are kept (merchants are not PII); the
 * `CaptureBackstopCorpusTest` pins the set to ZERO false positives on the
 * committed (already-redacted) corpus.
 */
object CustomerTextMarkers {

    /** Customer-PII label prefixes. Case-insensitive `startsWith` match. */
    val MARKERS: List<String> = listOf(
        "Deliver to ",
        "Order for ",
        "Verify items for ",
        "Delivery for ",
    )

    /** Substring that classifies a node's text as already-redacted (VET V1). */
    private const val REDACTED_MARK = "[redacted"

    /**
     * The first marker [text] carries UN-redacted, or null when clean. A node
     * whose text contains "[redacted" anywhere is treated as already-redacted and
     * skipped (VET V1) — otherwise a rule's OWN redact output ("Deliver to door
     * of [redacted:…]") would trip the "Deliver to " marker and re-scrub.
     */
    fun unredactedMarker(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        if (text.contains(REDACTED_MARK)) return null
        return MARKERS.firstOrNull { text.startsWith(it, ignoreCase = true) }
    }

    /**
     * The first un-redacted customer marker anywhere in [tree] (text or
     * contentDescription of any node), or null when clean. Cheap scan run on
     * every recognized capture; the [scrub] copy is built only on a hit.
     */
    fun firstUnredactedMarker(tree: UiNode): String? =
        tree.allText.firstNotNullOfOrNull { unredactedMarker(it) }

    /**
     * Return a copy of [tree] with every node whose `text`/`contentDescription`
     * carries an un-redacted customer marker scrubbed to [CompiledRedact.REDACTED].
     * Call only after [firstUnredactedMarker] returned non-null, so the tree copy
     * never happens on the clean path.
     */
    fun scrub(tree: UiNode): UiNode = tree.copy(
        text = if (unredactedMarker(tree.text) != null) CompiledRedact.REDACTED else tree.text,
        contentDescription =
            if (unredactedMarker(tree.contentDescription) != null) {
                CompiledRedact.REDACTED
            } else {
                tree.contentDescription
            },
        children = tree.children.map { scrub(it) },
    )
}
