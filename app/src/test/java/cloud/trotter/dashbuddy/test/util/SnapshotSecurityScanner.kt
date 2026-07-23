package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

/**
 * Scans a [UiNode] tree for sensitive keywords (PII, financial data).
 *
 * Used by [InboxProcessorTest] to prevent accidental commits of raw
 * banking/identity data in snapshot files.
 *
 * SSOT PARITY (#590): the marker/shape decision DELEGATES to the production
 * fail-closed backstop [SensitiveTextMarkers.findMarker], so the commit gate is
 * provably ≥ the runtime UNKNOWN-capture scrub — `findMarker(tree)!=null ⇒
 * scan(tree).isToxic` (pinned by `SnapshotSecurityScannerParityTest`). The scanner
 * adds only the corpus-gate EXTRA pin/gate shapes (#803) on top; it never
 * re-implements the keyword list, so the two copies can't diverge.
 */
object SnapshotSecurityScanner {

    /**
     * Regex SHAPES that mark PII a bare keyword can't (the #803 blind class). Each
     * pairs a pattern with a synthetic "keyword" label for the trigger report.
     *
     * - `pin \d{3,}` — a residence-entry PIN embedded in a free-text delivery-
     *   instructions body. This is the exact fragment that reached a corpus
     *   candidate id-less (no viewId → no rule/test id-scrub) and survived every
     *   layer; making it a corpus-gate failure stops recurrence in ANY future
     *   fixture. A `[\s:#]` separator class + no trailing `\b` (byte-aligned with the
     *   rule redact + [SnapshotRedactor.PIN]) catches "PIN: 4821"/"Pin4821";
     *   digit-adjacency (≥3 digits) keeps "PIN pad"/"pin it"/"opinion" clean.
     * - `gate \d{3,}` — a bare residence gate code ("gate 4821", no "code" token) in
     *   the same free-text body (#803 F1/F3). Same separator class + digit-adjacency.
     *
     * Deliberately NOT added: an embedded full-name bigram (`[A-Z][a-z]+ [A-Z][a-z]+`).
     * The scanner runs over the whole committed corpus, which legitimately carries
     * merchant/street/UI two-word capitalized phrases ("Maple Street", "Farmers
     * Market", "Complete Delivery"); a bigram scan would false-positive heavily on
     * driver-owned, non-PII text. The whole-value first-name+last-initial name shape
     * is already owned by [SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN] and pinned by
     * `CaptureRedactionCorpusTest` (FIX 4), so it needs no scanner duplicate.
     */
    private val SENSITIVE_SHAPES: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)\bpin[\s:#]*\d{3,}""") to "pin-code-shape",
        Regex("""(?i)\bgate[\s:#]*\d{3,}""") to "gate-code-shape",
    )

    data class ScanResult(
        val isToxic: Boolean,
        val triggers: List<Pair<String, String>> = emptyList()
    )

    fun scan(node: UiNode): ScanResult {
        val triggers = mutableListOf<Pair<String, String>>()

        // SSOT PARITY (#590): delegate the marker/shape decision to the production
        // fail-closed backstop rather than re-implementing its keyword list. This is
        // the load-bearing guarantee `findMarker(tree)!=null ⇒ scan.isToxic`:
        //  - it scans the whole tree's `allText` (text AND contentDescription), so a
        //    desc-borne marker the old per-node `node.text` walk missed is caught;
        //  - it NFKC-normalizes both sides, so homoglyph/whitespace-mutated markers hit;
        //  - it scans the space-joined blob, so a marker split across sibling nodes rejoins;
        //  - it owns the SSN/card-PAN shapes the scanner never had.
        // A future marker addition lands here automatically — the two copies can't diverge.
        SensitiveTextMarkers.findMarker(node)?.let { marker ->
            triggers.add(node.allText.joinToString(" ") to "sensitive-marker:$marker")
        }

        // Scanner-specific EXTRA corpus-gate shapes NOT in the production backstop
        // (the #803 id-less instructions-body pin/gate class). Kept on top of parity,
        // never in place of it.
        walkAndFind(node, triggers)
        return ScanResult(isToxic = triggers.isNotEmpty(), triggers = triggers)
    }

    fun printReport(result: ScanResult) {
        if (!result.isToxic) return

        println("     STATUS: TOXIC (Sensitive Data Detected)")
        println("     EVIDENCE:")

        if (result.triggers.isEmpty()) {
            println("        (Matched by Screen Type, no specific keyword triggers found)")
        } else {
            result.triggers.forEach { (text, keyword) ->
                println("        Found \"$text\" (Matched: '$keyword')")
            }
        }
        println("     ACTION: Redact these values in the JSON file immediately.")
    }

    /**
     * Walk the tree for the scanner-specific EXTRA shapes (#803 pin/gate). Marker/
     * keyword parity is owned by the [SensitiveTextMarkers.findMarker] delegation in
     * [scan]; this only adds the corpus-gate shapes the production backstop doesn't
     * carry. Scans both text and contentDescription so a shape hiding in a desc node
     * is caught too (parity discipline extended to the extras).
     */
    private fun walkAndFind(node: UiNode, results: MutableList<Pair<String, String>>) {
        for (field in listOfNotNull(node.text, node.contentDescription)) {
            SENSITIVE_SHAPES.firstOrNull { (re, _) -> re.containsMatchIn(field) }?.let { (_, label) ->
                results.add(field to label)
            }
        }
        node.children.forEach { walkAndFind(it, results) }
    }
}
