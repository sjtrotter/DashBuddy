package cloud.trotter.dashbuddy.core.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

/**
 * #1160 review NN5 — `SensitiveTextMarkers.normalize` now delegates NFKC + FORMAT strip + dash fold to the
 * shared `TextFold.foldGlyphs`. Its behaviour must be byte-for-byte the pre-#1160 loop, copied here
 * VERBATIM as the oracle, over hand-picked evasion strings and a seeded random sweep.
 */
class SensitiveTextMarkersNormalizePinTest {

    /** The pre-#1160 implementation, verbatim. */
    private fun legacyNormalize(s: String): String {
        val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC)
        val sb = StringBuilder(nfkc.length)
        for (ch in nfkc) {
            when {
                Character.getType(ch) == Character.FORMAT.toInt() -> {}
                ch in '‐'..'―' || ch == '−' -> sb.append('-')
                ch == '\u001F' || ch.isWhitespace() -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    @Test
    fun `normalize is byte-identical to the pre-delegation loop`() {
        val fixed = listOf(
            "Bank Account", "Bank Account", "Ｂａｎｋ　Ａｃｃｏｕｎｔ", "Ba​nk‍Acc﻿ount",
            "123‑45—6789", "4111−1111", "Transfer\u001F$45.66", "a\tb\nc\u000Bd",
            "🚗 car", "󠀁tag", "İstanbul", "ΣΊΣΥΦΟΣ", "ﬁnance", "①②③", "", "   ",
        )
        fixed.forEach { assertEquals(it, legacyNormalize(it), SensitiveTextMarkers.normalize(it)) }
        val rnd = Random(0x1160_0006L)
        val pool = "aZ09   ​‌‍﻿‐–―−\u001F\tＡＺ０９ßΣς́᠎ "
        repeat(2000) {
            val s = buildString { repeat(rnd.nextInt(0, 24)) { append(pool[rnd.nextInt(pool.length)]) } }
            assertEquals(s, legacyNormalize(s), SensitiveTextMarkers.normalize(s))
        }
    }
}
