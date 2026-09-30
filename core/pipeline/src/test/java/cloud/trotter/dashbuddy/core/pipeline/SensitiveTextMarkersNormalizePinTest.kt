package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

/**
 * #1160 reviews NN5/PP5/RR1/UU8 — the sensitive scan's TWO normal forms, each pinned:
 * - `normalizePreserving` (`TextFold.foldGlyphsPreservingSupplementary`) — byte-for-byte the pre-#1160
 *   normalizer, against the VERBATIM old loop;
 * - `normalize` — the preserving form minus its supplementary FORMAT code points (tag chars U+E0020–E007F,
 *   U+E0001, U+1D173–1D17A), no second NFKC (review WW1) — against a reference built from the verbatim loop.
 * `findMarker` drops on a hit in EITHER form, so the change only widens detection (toward privacy).
 */
class SensitiveTextMarkersNormalizePinTest {

    /**
     * The reference for the fully STRIPPED form (reviews RR1, WW1): the pre-#1160 normalizer ([legacyNormalize],
     * verbatim) with its supplementary-plane FORMAT code points removed — no further NFKC.
     */
    private fun referenceNormalize(s: String): String {
        val legacy = legacyNormalize(s)
        val sb = StringBuilder(legacy.length)
        var i = 0
        while (i < legacy.length) {
            val cp = legacy.codePointAt(i)
            if (!(cp >= 0x10000 && Character.getType(cp) == Character.FORMAT.toInt())) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    @Test
    fun `the stripped form matches the code-point reference and strips supplementary FORMAT chars`() {
        assertEquals("deliver to sam", SensitiveTextMarkers.normalize("Deli󠀠ver to Sam"))
        val fixed = listOf(
            "Bank Account", "Bank Account", "Ｂａｎｋ　Ａｃｃｏｕｎｔ",
            "Ba​nk‍Acc﻿ount", "123‑45—6789", "4111−1111", "Transfer\u001F\$45.66",
            "a\tb\nc\u000Bd", "🚗 car", "󠀁tag", "Deli󠀠ver to Sam",
            "Bank𝅳 Account", "İstanbul", "ΣΊΣΥΦΟΣ", "ﬁnance",
            "①②③", "", "   ",
        )
        fixed.forEach { assertEquals(it, referenceNormalize(it), SensitiveTextMarkers.normalize(it)) }
        val rnd = Random(0x1160_0006L)
        val pool = listOf(
            "a", "Z", "0", "9", " ", " ", " ", "​", "‌", "‍", "﻿", "‐", "–",
            "―", "−", "\u001F", "\t", "Ａ", "０", "ß", "Σ", "ς", "́", "᠎",
            " ", "󠀠", "𝅳", "🚗",
        )
        repeat(2000) {
            val s = buildString { repeat(rnd.nextInt(0, 24)) { append(pool[rnd.nextInt(pool.size)]) } }
            assertEquals(s, referenceNormalize(s), SensitiveTextMarkers.normalize(s))
        }
    }

    /** The pre-#1160 normalizer, VERBATIM (per UTF-16 unit) — the oracle for the preserved form (RR1). */
    private fun legacyNormalize(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when {
                Character.getType(ch) == Character.FORMAT.toInt() -> {}
                ch in '\u2010'..'\u2015' || ch == '\u2212' -> sb.append('-')
                ch == '\u001F' || ch.isWhitespace() -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    @Test
    fun `the boundary-preserving form is byte-for-byte the pre-1160 normalizer (review RR1)`() {
        val rnd = Random(0x1160_0008L)
        val pool = listOf(
            "a", "Z", "0", " ", "\u00A0", "\u200B", "\u200D", "\uFEFF", "\u2011", "\u2212", "\u001F", "\uFF21",
            "\u0301", "\uDB40\uDC20", "\uD834\uDD73", "\uD83D\uDE97", "-", "1",
        )
        val inputs = listOf("x\uDB40\uDC20123-45-6789", "Deli\uDB40\uDC20ver to Sam", "Bank\u200BAccount") +
            List(2000) { buildString { repeat(rnd.nextInt(0, 24)) { append(pool[rnd.nextInt(pool.size)]) } } }
        inputs.forEach { assertEquals(it, legacyNormalize(it), SensitiveTextMarkers.normalizePreserving(it)) }
    }

    @Test
    fun `a supplementary FORMAT char beside a shape cannot hide it - either form hits (review RR1)`() {
        listOf(
            "x\uDB40\uDC20123-45-6789", "123-45-6789\uDB40\uDC20x",
            "x\uDB40\uDC204111 1111 1111 1111", "4111 1111 1111 1111\uDB40\uDC20x",
            "Deli\uDB40\uDC20ver to Sam", "Bank\uDB40\uDC20 Account",
        ).forEach { text ->
            if (text.contains("Deli")) return@forEach // a customer marker, not a sensitive one
            assertTrue(text, SensitiveTextMarkers.findMarker(text) != null)
            assertTrue(text, SensitiveTextMarkers.findMarker(UiNode(text = text)) != null)
        }
    }

    @Test
    fun `the two forms differ ONLY when a supplementary FORMAT char is present, so the scan skip is exact (review WW3)`() {
        val rnd = Random(0x1160_0010L)
        val bmpPool = listOf("a", "Z", "1", "-", " ", "\u00A0", "\u200B", "\u200D", "\u0301", "\uFF21", "\u2013", "\uD83D\uDE97")
        repeat(2000) {
            val s = buildString { repeat(rnd.nextInt(0, 24)) { append(bmpPool[rnd.nextInt(bmpPool.size)]) } }
            assertEquals(s, SensitiveTextMarkers.normalizePreserving(s), SensitiveTextMarkers.normalize(s))
        }
        // A combining mark after an SSN / PAN: both forms agree without a tag char…
        listOf("123-45-6789a\u200D\u0301", "4111 1111 1111 1111a\u200D\u0301").forEach {
            assertEquals(SensitiveTextMarkers.normalizePreserving(it), SensitiveTextMarkers.normalize(it))
        }
        // …and with one they differ, and the union still hits.
        listOf("123-45-6789\uDB40\uDC20\u0301", "4111 1111 1111 1111\uDB40\uDC20\u0301", "Vis\uDB40\uDC20a\u200D\u0301").forEach {
            assertTrue(it, SensitiveTextMarkers.normalizePreserving(it) != SensitiveTextMarkers.normalize(it))
            assertTrue(it, SensitiveTextMarkers.findMarker(it) != null)
        }
    }

    @Test
    fun `every keyword and shape pattern is ASCII - the coupling the scan skip relies on (review XX8)`() {
        // The skip in `scanBothForms` is exact because the stripped form is the preserving form minus its
        // supplementary FORMAT code points; ASCII keywords/shapes make that independent of any other fold
        // difference. A non-ASCII entry must make this coupling visible, not silently weaken the skip.
        SensitiveTextMarkers.KEYWORDS.forEach { assertTrue(it, it.all { c -> c.code < 0x80 }) }
        SensitiveTextMarkers.SHAPE_PATTERNS.forEach { assertTrue(it.pattern, it.pattern.all { c -> c.code < 0x80 }) }
    }
}
