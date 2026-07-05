package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * #590 — MARKER EVASION RESISTANCE for the sensitive-screen pledge backstop.
 *
 * [SensitiveTextMarkers] is the fail-closed guard the matcher-layer block leans on:
 * an unrecognized banking/identity screen classifies UNKNOWN and is scrubbed from
 * the capture ONLY if [SensitiveTextMarkers.findMarker] hits. Before this suite the
 * scan was a plain case-insensitive substring test, so any homoglyph/whitespace
 * mutation of a toxic seed marker evaded it and the raw screen shipped to disk.
 *
 * Red-first observation (pre-hardening, current `contains(..., ignoreCase = true)`):
 * every non-case mutation class below EVADED the substring scan and returned
 * `null` (clean) on a genuinely toxic screen —
 *  - NBSP (U+00A0) / narrow-NBSP (U+202F) substituted for the ASCII space,
 *  - zero-width chars (U+200B/200C/200D/FEFF) injected between letters,
 *  - unicode dashes (U+2010–U+2015) substituted for the ASCII hyphen in the SSN/PAN shapes,
 *  - fullwidth digits/letters (U+FF10+),
 *  - the marker split across two adjacent sibling text nodes.
 * Only the plain case-variant mutation was already caught (`ignoreCase = true`).
 *
 * The fix normalizes BOTH the scanned text and the markers through one single-pass
 * [SensitiveTextMarkers] normalizer (NFKC + zero-width/format strip + unicode-dash
 * fold + whitespace canonicalization + `Locale.ROOT` lowercase) before the substring
 * scan, and — for the sibling-split class — scans the space-joined `allText` form
 * (the existing tree walk, no new traversal). The normalization is fail-CLOSED: any
 * throw is treated as a hit, never as "clean".
 *
 * Documented out-of-scope residuals (see the bottom of this file) are asserted as
 * residuals, not detections, so a future hardening that closes them is a visible
 * behaviour change.
 */
class SensitiveMarkerEvasionTest {

    // ---- Seed toxic markers to mutate (a representative spread) -----------------
    // Multi-word (space-bearing) keywords, a single-word keyword, and the two shapes.
    private val keywordSeeds = listOf(
        "Routing Number",
        "Bank Account",
        "Available Balance",
        "DasherDirect",
        "CVV",
    )
    private val ssnSeed = "123-45-6789"
    private val panSeed = "4111 1111 1111 1111"

    private fun tree(vararg texts: String) = UiNode(children = texts.map { UiNode(text = it) })

    // ---- Mutations --------------------------------------------------------------

    private fun nbsp(s: String) = s.replace(' ', ' ')
    private fun narrowNbsp(s: String) = s.replace(' ', ' ')
    private fun zeroWidth(s: String) = s.toCharArray().joinToString("​")
    private fun unicodeDash(s: String) = s.replace('-', '‐')
    private fun fullwidth(s: String) = s.map { c ->
        val code = when (c) {
            in '0'..'9' -> '０'.code + (c - '0')
            in 'a'..'z' -> 'ａ'.code + (c - 'a')
            in 'A'..'Z' -> 'Ａ'.code + (c - 'A')
            ' ' -> '　'.code // ideographic space — NFKC-folds to a normal space
            else -> c.code
        }
        code.toChar()
    }.joinToString("")
    private fun altCase(s: String) = s.mapIndexed { i, c ->
        if (i % 2 == 0) c.uppercaseChar() else c.lowercaseChar()
    }.joinToString("")

    /** Split [s] at its first space into two adjacent sibling text nodes. */
    private fun splitSiblings(s: String): UiNode {
        val idx = s.indexOf(' ')
        return if (idx < 0) tree(s) else tree(s.substring(0, idx), s.substring(idx + 1))
    }

    private fun assertDetected(mutation: String, seed: String, tree: UiNode) {
        assertNotNull(
            "EVASION: mutation '$mutation' of toxic marker <$seed> was NOT detected — " +
                "a genuinely sensitive screen would ship to disk",
            SensitiveTextMarkers.findMarker(tree),
        )
    }

    private fun assertDetected(mutation: String, seed: String, text: String) {
        assertNotNull(
            "EVASION: mutation '$mutation' of toxic marker <$seed> was NOT detected (flat overload)",
            SensitiveTextMarkers.findMarker(text),
        )
    }

    // ---- Keyword mutations ------------------------------------------------------

    @Test
    fun `NBSP-substituted keyword markers are still detected`() {
        for (seed in keywordSeeds) {
            assertDetected("NBSP tree", seed, tree(nbsp(seed)))
            assertDetected("NBSP flat", seed, nbsp(seed))
        }
    }

    @Test
    fun `narrow-NBSP-substituted keyword markers are still detected`() {
        for (seed in keywordSeeds) {
            assertDetected("narrow-NBSP tree", seed, tree(narrowNbsp(seed)))
            assertDetected("narrow-NBSP flat", seed, narrowNbsp(seed))
        }
    }

    @Test
    fun `zero-width-injected keyword markers are still detected`() {
        for (seed in keywordSeeds) {
            assertDetected("ZW tree", seed, tree(zeroWidth(seed)))
            assertDetected("ZW flat", seed, zeroWidth(seed))
        }
    }

    @Test
    fun `fullwidth keyword markers are still detected`() {
        for (seed in keywordSeeds) {
            assertDetected("fullwidth tree", seed, tree(fullwidth(seed)))
            assertDetected("fullwidth flat", seed, fullwidth(seed))
        }
    }

    @Test
    fun `case-variant keyword markers are still detected (already covered pre-fix)`() {
        for (seed in keywordSeeds) {
            assertDetected("altCase tree", seed, tree(altCase(seed)))
            assertDetected("altCase flat", seed, altCase(seed))
        }
    }

    @Test
    fun `keyword markers split across adjacent sibling nodes are still detected`() {
        // Only the space-bearing multi-word keywords can split; single-word keywords
        // can't and are covered by the per-node path above.
        for (seed in keywordSeeds.filter { it.contains(' ') }) {
            assertDetected("sibling-split", seed, splitSiblings(seed))
        }
    }

    // ---- Shape mutations (SSN / card PAN) --------------------------------------

    @Test
    fun `unicode-dash SSN shape is still detected`() {
        assertDetected("unicode-dash SSN tree", ssnSeed, tree(unicodeDash(ssnSeed)))
        assertDetected("unicode-dash SSN flat", ssnSeed, unicodeDash(ssnSeed))
    }

    @Test
    fun `fullwidth-digit SSN shape is still detected`() {
        assertDetected("fullwidth SSN tree", ssnSeed, tree(fullwidth(ssnSeed)))
        assertDetected("fullwidth SSN flat", ssnSeed, fullwidth(ssnSeed))
    }

    @Test
    fun `NBSP-separated card PAN shape is still detected`() {
        assertDetected("NBSP PAN tree", panSeed, tree(nbsp(panSeed)))
        assertDetected("NBSP PAN flat", panSeed, nbsp(panSeed))
    }

    @Test
    fun `fullwidth-digit card PAN shape is still detected`() {
        assertDetected("fullwidth PAN tree", panSeed, tree(fullwidth(panSeed)))
        assertDetected("fullwidth PAN flat", panSeed, fullwidth(panSeed))
    }

    // ---- Property: random composition of the keyword mutations still detected ---

    @Test
    fun `property - any composition of homoglyph-whitespace mutations still detects a keyword`() = runTest {
        val seed = Arb.element(keywordSeeds)
        val mut = Arb.element<(String) -> String>(
            ::nbsp, ::narrowNbsp, ::zeroWidth, ::fullwidth, ::altCase,
        )
        checkAll(200, seed, mut, mut) { s, m1, m2 ->
            val mutated = m2(m1(s))
            assertDetected("composed", s, tree(mutated))
        }
    }

    // ---- Fail-closed: normalization never returns "clean" on a throw -----------
    // (Covered structurally: the normalizer catches all Throwable and returns a
    // toxic sentinel. A deliberately malformed lone-surrogate string exercises the
    // path without crashing.) A lone surrogate is not valid for NFKC on some JDKs.

    @Test
    fun `lone-surrogate text never crashes the scan`() {
        // Must not throw; result may be null (no marker) or non-null (fail-closed).
        SensitiveTextMarkers.findMarker(tree("\uD800 balance"))
        SensitiveTextMarkers.findMarker("\uD800 balance")
    }

    // ---- Documented out-of-scope RESIDUALS -------------------------------------
    // A plain single-token shape (SSN) split across two sibling nodes as
    // "123-45" / "6789" IS caught (the space-joined form re-assembles it), but a
    // PAN split so that no 4-4-4-4 run survives the join is a residual — the shape
    // needs the digit groups contiguous. The rule-declared sensitive rules remain
    // the primary control for shaped values; these markers are the backstop.
    @Test
    fun `RESIDUAL - a fully fragmented PAN (one digit per node) is NOT caught by the shape backstop`() {
        // Documented residual: 16 single-digit sibling nodes never form a 4-4-4-4
        // run in the joined text, so the PAN shape can't match. Recognized banking
        // screens (the primary control) still block this; the marker backstop does not.
        val digits = panSeed.filter { it.isDigit() }.map { it.toString() }
        val fragmented = UiNode(children = digits.map { UiNode(text = it) })
        // Assert the residual explicitly so closing it later is a visible change.
        org.junit.Assert.assertNull(
            "if this becomes non-null, the residual was closed — update the doc",
            SensitiveTextMarkers.findMarker(fragmented),
        )
    }

    // Sanity: the normalizer must not turn CLEAN screens toxic (Principle: zero
    // behaviour change on the clean corpus).
    @Test
    fun `ordinary screens stay clean after normalization`() {
        org.junit.Assert.assertNull(
            SensitiveTextMarkers.findMarker(
                tree("Pickup from Chipotle", "Deliver by 7:45 PM", "\$7.50", "3.2 mi", "Accept"),
            ),
        )
        org.junit.Assert.assertNull(
            SensitiveTextMarkers.findMarker(tree("Total: \$1,234.56", "ETA 12-45")),
        )
    }
}
