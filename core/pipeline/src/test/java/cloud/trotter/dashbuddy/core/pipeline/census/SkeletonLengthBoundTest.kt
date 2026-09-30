package cloud.trotter.dashbuddy.core.pipeline.census

import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.KindClassifier
import cloud.trotter.dashbuddy.domain.census.contract.TextFold
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import java.text.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1160 review AB8 — the pre-fold length bound: a value with more than
 * `MAX_COMPOSITION_RATIO × MAX_TOKEN_LENGTH` non-FORMAT, non-whitespace code points is `LENGTH_CAP` without
 * ever folding, and the bound's two premises hold for EVERY code point on the running JVM.
 */
class SkeletonLengthBoundTest : SkeletonBuilderTestBase() {

    private val bound = SkeletonBuilder.MAX_COMPOSITION_RATIO * SkeletonBuilder.MAX_TOKEN_LENGTH

    private fun counted(cp: Int) = !TextFold.isFormat(cp) && !KindClassifier.isWhitespace(cp)

    @Test
    fun `premise - no canonical decomposition is longer than the composition ratio`() {
        var longest = 0
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF) continue
            val nfd = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
            longest = maxOf(longest, nfd.codePointCount(0, nfd.length))
        }
        assertTrue("longest canonical decomposition $longest", longest <= SkeletonBuilder.MAX_COMPOSITION_RATIO)
        assertEquals(4, Normalizer.normalize("ᾂ", Normalizer.Form.NFD).codePointCount(0, 4))
    }

    @Test
    fun `premise - every counted code point keeps a counted code point through NFKD`() {
        val lost = ArrayList<Int>()
        for (cp in 0..0x10FFFF) {
            if (cp in 0xD800..0xDFFF || !counted(cp)) continue
            val nfkd = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)
            if (nfkd.codePoints().noneMatch { counted(it) }) lost += cp
        }
        assertEquals(emptyList<Int>(), lost.map { Integer.toHexString(it) })
    }

    @Test
    fun `a 5 KB body is LENGTH_CAP without a single fold`() {
        val folds = HashMap<String, Int>()
        val filter = SkeletonBuilder.FrameFilter(
            judge = SkeletonBuilder::withholdingStep,
            canonicalize = { v -> folds.merge(v, 1, Int::plus); CensusHash.canonical(v) },
        )
        val body = "Lorem ipsum dolor sit amet ".repeat(190)
        val root = filter.emit(filter.scan(UiNode(className = "android.widget.TextView", text = body, contentDescription = "Accept")))
        assertEquals(TextSlot.WITHHELD, root.text.getValue("text"))
        assertEquals(words(1, "Accept"), root.text.getValue("desc"))
        assertEquals(mapOf("Accept" to 1), folds)
    }

    @Test
    fun `the bound is exact at its edge - over it skips the fold, at it folds`() {
        val over = "a".repeat(bound + 1)
        val at = "a".repeat(bound)
        assertTrue(SkeletonBuilder.provablyOverCap(over))
        assertTrue(!SkeletonBuilder.provablyOverCap(at))
        // Whitespace and FORMAT code points are never counted.
        assertTrue(!SkeletonBuilder.provablyOverCap("a ​".repeat(bound / 2)))
        // Both paths withhold (the at-bound value is still over the 40-char cap after folding).
        assertEquals(TextSlot.WITHHELD, slot(over))
        assertEquals(TextSlot.WITHHELD, slot(at))
    }
}
