package cloud.trotter.dashbuddy.domain.census.contract

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
}
