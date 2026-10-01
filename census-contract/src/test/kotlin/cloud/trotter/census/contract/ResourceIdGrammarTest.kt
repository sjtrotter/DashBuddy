package cloud.trotter.census.contract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §1 / #1160 review AA10 — the static resource-name grammar against its shared vectors. */
class ResourceIdGrammarTest {

    @Test
    fun `static vectors pass, dynamic vectors are absent`() {
        ResourceIdGrammarVectors.STATIC.forEach { assertTrue(it, ResourceIdGrammar.isStaticShape(it)) }
        ResourceIdGrammarVectors.DYNAMIC.forEach { assertFalse(it, ResourceIdGrammar.isStaticShape(it)) }
    }

    @Test
    fun `the three committed dynamic ids share one fingerprint once absent`() {
        val prints = ResourceIdGrammarVectors.DYNAMIC.take(3).map {
            CensusFingerprint.of(
                UiSkeletonNodeDto(className = "android.view.View", id = "com.x:id/host", children = listOf(
                    UiSkeletonNodeDto(className = "android.widget.Button", id = it.takeIf { id -> ResourceIdGrammar.isStaticShape(id) }),
                )),
            )
        }.toSet()
        assertTrue(prints.size == 1)
    }

    @Test
    fun `the frame-withheld sentinel is the one non-grammar wire id (review AC3)`() {
        assertFalse(ResourceIdGrammar.isStaticShape(ResourceIdGrammar.FRAME_WITHHELD_ID))
        assertTrue(ResourceIdGrammar.isWireId("~"))
        listOf("~~", " ~", "com.x:id/~", "").forEach { assertFalse(it, ResourceIdGrammar.isWireId(it)) }
        UiSkeletonNodeDto(className = "android.widget.LinearLayout", id = "~")
        assertTrue(runCatching { UiSkeletonNodeDto(id = "~~") }.isFailure)
        // The sentinel keeps a wrapper-class node in the byte form: it is not spliced like a null id.
        val sentinel = CensusFingerprint.of(UiSkeletonNodeDto(className = "android.widget.LinearLayout", id = "~", children = listOf(
            UiSkeletonNodeDto(className = "android.widget.Button"),
        )))
        val spliced = CensusFingerprint.of(UiSkeletonNodeDto(className = "android.widget.LinearLayout", children = listOf(
            UiSkeletonNodeDto(className = "android.widget.Button"),
        )))
        assertTrue(sentinel != spliced)
    }

    @Test
    fun `both static grammars share one dynamic-run rule (review AJ4)`() {
        val dynamic = listOf("a3f488d4a", "Tag1234", "x0f0b4fb9c", "row_2024")
        val static = listOf("a3f488d", "Tag123", "bc25_fab", "a11y")
        dynamic.forEach {
            assertTrue(it, DynamicRuns.hasDynamicRun(it))
            assertFalse(it, ClassNameGrammar.isStatic("com.x.$it"))
            assertFalse(it, ResourceIdGrammar.isStaticShape("com.x:id/$it"))
        }
        static.forEach {
            assertFalse(it, DynamicRuns.hasDynamicRun(it))
            assertTrue(it, ClassNameGrammar.isStatic("com.x.$it"))
            assertTrue(it, ResourceIdGrammar.isStaticShape("com.x:id/$it"))
        }
    }

    @Test
    fun `the id grammar rejects a mask bracket, so no mask can reach the id path (review AJ8)`() {
        listOf("[redacted]", "chip_[name]", "x:id/[redacted:ab12]", "tag[").forEach { assertFalse(it, ResourceIdGrammar.isStaticShape(it)) }
    }

    @Test
    fun `a letter-only hex run is a word, not a dynamic value (review AL1)`() {
        listOf("android.view.HapticFeedbackConstants", "com.doordash.ui.AddedBadgeView", "androidx.core.provider.AsyncTypefaceCache")
            .forEach { assertTrue(it, ClassNameGrammar.isStatic(it)) }
        assertFalse(DynamicRuns.hasDynamicRun("DefaultHapticFeedback"))
        listOf("3f488d4a", "a3f488d4a", "deadbeef1").forEach { assertTrue(it, DynamicRuns.hasDynamicRun(it)) }
        assertFalse(ResourceIdGrammar.isStaticShape("PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a"))
        assertTrue(ResourceIdGrammar.isStaticShape("com.x:id/added_badge_view"))
    }

    @Test
    fun `a UUID-shaped token is dynamic regardless of digits (review AM2)`() {
        assertTrue(DynamicRuns.hasDynamicRun("PRIMARY_BUTTON_deadbeef-acde-4abc-acde-acdeabcdefab"))
        assertTrue(DynamicRuns.hasDynamicRun("deadbeef-acde-abcd-acde-acdeabcdefab"))
        assertFalse(ResourceIdGrammar.isStaticShape("PRIMARY_BUTTON_deadbeef-acde-4abc-acde-acdeabcdefab"))
        // A letter-only word is still not dynamic.
        assertFalse(DynamicRuns.hasDynamicRun("AddedBadgeView"))
    }
}
