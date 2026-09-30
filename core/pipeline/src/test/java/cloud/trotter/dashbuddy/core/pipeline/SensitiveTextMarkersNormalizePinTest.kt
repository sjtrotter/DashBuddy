package cloud.trotter.dashbuddy.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

/**
 * #1160 reviews NN5/PP5 — `SensitiveTextMarkers.normalize` delegates NFKC + FORMAT strip + dash fold to the
 * shared `TextFold.foldGlyphs`. Its behaviour is pinned against a REFERENCE implementation: the pre-#1160
 * loop with ONE deliberate change (review PP5) — FORMAT is judged per CODE POINT, so a supplementary-plane
 * FORMAT char (tag chars U+E0020–E007F, U+E0001, U+1D173–1D17A) is stripped instead of surviving as a
 * surrogate pair that splits a marker. That widens detection only (toward privacy).
 */
class SensitiveTextMarkersNormalizePinTest {

    /** The reference: the pre-#1160 loop, FORMAT judged per code point (review PP5). */
    private fun referenceNormalize(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        var i = 0
        while (i < nfkc.length) {
            val cp = nfkc.codePointAt(i)
            val n = Character.charCount(cp)
            when {
                Character.getType(cp) == Character.FORMAT.toInt() -> {}
                cp in 0x2010..0x2015 || cp == 0x2212 -> sb.append('-')
                n == 1 && (nfkc[i] == '\u001F' || nfkc[i].isWhitespace()) -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
            i += n
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    @Test
    fun `normalize matches the reference and strips supplementary FORMAT chars`() {
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
}
