package cloud.trotter.dashbuddy.test.util

import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #590 — SSOT PARITY between the test-side corpus commit gate
 * ([SnapshotSecurityScanner.scan]) and the runtime fail-closed backstop
 * ([SensitiveTextMarkers.findMarker]).
 *
 * The scanner is what stops a raw banking/identity screen from being committed
 * into the golden corpus; the backstop is what drops the same screen from an
 * on-device UNKNOWN capture. If the scanner is WEAKER than the backstop, a fixture
 * the runtime would refuse to keep can still land in the repo — the exact #362-class
 * divergence-bug (two hand-maintained copies of "what is toxic" drift apart).
 *
 * The invariant: **for every tree, `findMarker(tree) != null ⇒ scan(tree).isToxic`.**
 * The scanner must catch *at least* everything the production backstop catches.
 *
 * Red-first observation (pre-fix, `scan` walking `node.text` with a plain
 * `contains(..., ignoreCase = true)` + only the pin/gate shapes):
 *  - a marker in a node's **contentDescription** (which `findMarker` scans via
 *    `allText`, but the old per-node `scan` never read) → findMarker HIT, scan CLEAN;
 *  - an **SSN / card PAN** shape (in `findMarker.SHAPE_PATTERNS`, absent from the
 *    scanner) → findMarker HIT, scan CLEAN;
 *  - a **homoglyph/whitespace-mutated** marker (NBSP / zero-width / fullwidth — which
 *    `findMarker` normalizes, but the old plain `contains` missed) → findMarker HIT,
 *    scan CLEAN;
 *  - a marker **split across two adjacent sibling nodes** (`findMarker` scans the
 *    space-joined `allText`, the old per-node scan never rejoined) → findMarker HIT,
 *    scan CLEAN.
 * Each class made the implication FALSE, so this suite starts red.
 *
 * The fix brings the scanner to SSOT parity by DELEGATING the marker decision to
 * `SensitiveTextMarkers.findMarker` (not by duplicating its list), keeping the
 * scanner-specific EXTRA pin/gate corpus-gate shapes on top. Delegation is what makes
 * a future marker addition impossible to diverge.
 */
class SnapshotSecurityScannerParityTest {

    // Representative spread of production markers + the two shapes findMarker owns.
    private val toxicTokens: List<String> = listOf(
        "Bank Account",
        "Routing Number",
        "Social Security",
        "DasherDirect",
        "Available Balance",
        "CVV",
        "Transfer to bank",
        "123-45-6789",       // SSN shape
        "4111 1111 1111 1111", // card PAN shape
    )

    // Benign filler so the toxic token is not the whole tree.
    private val benign = listOf(
        "Pickup from Chipotle", "Deliver by 7:45 PM", "\$7.50", "3.2 mi", "Accept", "Decline",
    )

    // ---- Homoglyph / whitespace mutations (findMarker normalizes; old scan didn't) ----
    private fun nbsp(s: String) = s.replace(' ', ' ')
    private fun zeroWidth(s: String) = s.toCharArray().joinToString("​")
    private fun fullwidth(s: String) = s.map { c ->
        when (c) {
            in '0'..'9' -> ('０'.code + (c - '0')).toChar()
            in 'a'..'z' -> ('ａ'.code + (c - 'a')).toChar()
            in 'A'..'Z' -> ('Ａ'.code + (c - 'A')).toChar()
            ' ' -> '　' // ideographic space (NFKC-folds to a normal space)
            else -> c
        }
    }.joinToString("")
    private fun identity(s: String) = s

    private val mutations: List<(String) -> String> = listOf(::identity, ::nbsp, ::zeroWidth, ::fullwidth)

    /** Placements that exercise the parity gaps: text node, desc node, sibling-split. */
    private enum class Placement { TEXT, DESC, SPLIT_SIBLINGS }

    private fun buildTree(token: String, placement: Placement): UiNode = when (placement) {
        Placement.TEXT ->
            UiNode(children = listOf(UiNode(text = benign[0]), UiNode(text = token))).restoreParents()
        Placement.DESC ->
            UiNode(children = listOf(UiNode(text = benign[1]), UiNode(contentDescription = token)))
                .restoreParents()
        Placement.SPLIT_SIBLINGS -> {
            val idx = token.indexOf(' ')
            val kids = if (idx < 0) {
                listOf(UiNode(text = token))
            } else {
                listOf(UiNode(text = token.substring(0, idx)), UiNode(text = token.substring(idx + 1)))
            }
            UiNode(children = kids).restoreParents()
        }
    }

    private val treeArb: Arb<UiNode> = arbitrary { rs ->
        val token = toxicTokens[rs.random.nextInt(toxicTokens.size)]
        val mutated = mutations[rs.random.nextInt(mutations.size)](token)
        val placement = Placement.entries[rs.random.nextInt(Placement.entries.size)]
        buildTree(mutated, placement)
    }

    @Test
    fun `property - scanner catches everything the production backstop catches`() = runTest {
        checkAll(600, treeArb) { tree ->
            val markerHit = SensitiveTextMarkers.findMarker(tree)
            if (markerHit != null) {
                assertTrue(
                    "SSOT PARITY VIOLATION: findMarker hit <$markerHit> on\n$tree\n" +
                        "but SnapshotSecurityScanner.scan reported CLEAN — a toxic screen the " +
                        "runtime would drop could be committed to the corpus.",
                    SnapshotSecurityScanner.scan(tree).isToxic,
                )
            }
        }
    }

    // ---- Crisp named cases (each isolates one pre-fix gap for the red-first log) ----

    @Test
    fun `marker in contentDescription is toxic`() {
        val tree = UiNode(contentDescription = "Routing Number 021000021").restoreParents()
        assertNotNull("backstop must hit a desc-borne marker", SensitiveTextMarkers.findMarker(tree))
        assertTrue("scanner must match the backstop on desc", SnapshotSecurityScanner.scan(tree).isToxic)
    }

    @Test
    fun `SSN shape is toxic`() {
        val tree = UiNode(text = "SSN 123-45-6789 on file").restoreParents()
        assertNotNull(SensitiveTextMarkers.findMarker(tree))
        assertTrue(SnapshotSecurityScanner.scan(tree).isToxic)
    }

    @Test
    fun `card PAN shape is toxic`() {
        val tree = UiNode(text = "4111 1111 1111 1111").restoreParents()
        assertNotNull(SensitiveTextMarkers.findMarker(tree))
        assertTrue(SnapshotSecurityScanner.scan(tree).isToxic)
    }

    @Test
    fun `homoglyph-mutated marker is toxic`() {
        val tree = UiNode(text = nbsp("Available Balance")).restoreParents()
        assertNotNull(SensitiveTextMarkers.findMarker(tree))
        assertTrue(SnapshotSecurityScanner.scan(tree).isToxic)
    }

    @Test
    fun `marker split across sibling nodes is toxic`() {
        val tree = UiNode(children = listOf(UiNode(text = "Bank"), UiNode(text = "Account"))).restoreParents()
        assertNotNull(SensitiveTextMarkers.findMarker(tree))
        assertTrue(SnapshotSecurityScanner.scan(tree).isToxic)
    }

    // ---- The scanner-specific EXTRA (pin/gate) must SURVIVE the delegation refactor ----

    @Test
    fun `scanner keeps its pin and gate corpus-gate shapes after delegating markers`() {
        val pinTree = UiNode(text = "Leave at door. pin 1234 to enter").restoreParents()
        val pinScan = SnapshotSecurityScanner.scan(pinTree)
        assertTrue("pin shape still toxic", pinScan.isToxic)
        assertTrue(
            "pin-code-shape trigger preserved",
            pinScan.triggers.any { it.second == "pin-code-shape" },
        )
        // findMarker does NOT own the pin shape — this is a scanner-only extra, proving
        // the delegation did not REPLACE the scanner's own checks.
        // (no assertion on findMarker here: it may legitimately be null for a bare pin code)
    }
}
