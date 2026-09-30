package cloud.trotter.dashbuddy.domain.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** #1160 review AL4 — the shared text cap cuts at a code-point boundary. */
class UiTextBoundsTest {

    @Test
    fun `a cut that would split a surrogate pair drops the whole pair`() {
        val tag = "a".repeat(UiTextBounds.MAX_TEXT_LENGTH - 1) + "\uD83D\uDE97" + "tail"
        val capped = UiTextBounds.cap(tag)
        assertEquals("a".repeat(UiTextBounds.MAX_TEXT_LENGTH - 1), capped)
        assertFalse(Character.isHighSurrogate(capped.last()))
        // A BMP boundary is unchanged, and a short value is untouched.
        assertEquals(UiTextBounds.MAX_TEXT_LENGTH, UiTextBounds.cap("b".repeat(5000)).length)
        assertEquals("ok", UiTextBounds.cap("ok"))
        assertEquals("abc", UiTextBounds.cap("abc\uD83D\uDE97", 4))
    }
}
