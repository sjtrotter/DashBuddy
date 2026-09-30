package cloud.trotter.dashbuddy.domain.census.contract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §1 / #1160 review AA10 — the static resource-name grammar against its shared vectors. */
class ResourceIdGrammarTest {

    @Test
    fun `static vectors pass, dynamic vectors are absent`() {
        ResourceIdGrammarVectors.STATIC.forEach { assertTrue(it, ResourceIdGrammar.isStatic(it)) }
        ResourceIdGrammarVectors.DYNAMIC.forEach { assertFalse(it, ResourceIdGrammar.isStatic(it)) }
        assertNull(ResourceIdGrammar.staticOrNull(ResourceIdGrammarVectors.DYNAMIC.first()))
        assertNull(ResourceIdGrammar.staticOrNull(null))
    }

    @Test
    fun `the three committed dynamic ids share one fingerprint once absent`() {
        val prints = ResourceIdGrammarVectors.DYNAMIC.take(3).map {
            CensusFingerprint.of(
                UiSkeletonNodeDto(className = "android.view.View", id = "com.x:id/host", children = listOf(
                    UiSkeletonNodeDto(className = "android.widget.Button", id = ResourceIdGrammar.staticOrNull(it)),
                )),
            )
        }.toSet()
        assertTrue(prints.size == 1)
    }
}
