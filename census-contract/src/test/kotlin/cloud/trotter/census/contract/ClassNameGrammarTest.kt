package cloud.trotter.census.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1160 review round 2 — CC1 class grammar, CC6 single owners (`isLowerHex`, `measure`). */
class ClassNameGrammarTest {

    @Test
    fun `static class names pass, dynamic or text-like ones are absent`() {
        listOf(
            "android.widget.TextView", "androidx.compose.ui.platform.ComposeView", "android.view.View",
            "com.example.Outer\$Inner", "_Private", "a11y.Clock",
        ).forEach { assertTrue(it, ClassNameGrammar.isStatic(it)) }
        listOf(
            "Composable_3f488d4a", "Row1234", "Jane Smith's button", "", ".leading", "trailing.",
            "a..b", "x".repeat(129), "com.example.Button-1",
        ).forEach { assertFalse(it, ClassNameGrammar.isStatic(it)) }
        assertNull(ClassNameGrammar.staticOrNull("Row1234"))
    }

    @Test
    fun `one lower-hex owner and one size owner`() {
        assertTrue(WireStrings.isLowerHex("0123456789abcdef", 16))
        assertFalse(WireStrings.isLowerHex("0123456789ABCDEF", 16))
        assertFalse(WireStrings.isLowerHex("0123456789abcdef", 64))
        val root = UiSkeletonNodeDto(className = "android.widget.TextView")
        val item = UiSkeletonDto(
            schemaId = SkeletonSchema.SCHEMA_ID, hashDomain = 1, filterRev = 1,
            fingerprint = CensusFingerprint.of(root)!!, platform = "doordash", engineVersion = 1,
            day = "2026-09-30", root = root,
        )
        val m = SkeletonSchema.measure(item)
        assertEquals(SkeletonSchema.serialize(item), m.json)
        assertEquals(m.json.toByteArray(Charsets.UTF_8).size, m.bytes)
    }
}
