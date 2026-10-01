package cloud.trotter.census.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADR-0011 §1 — the grammar against the shared golden vectors, plus the wire-kind predicates. */
class KindClassifierTest {

    @Test
    fun `every shared golden vector classifies to its expected shape`() {
        val failures = KindClassifierVectors.VECTORS.mapNotNull { v ->
            val actual = KindClassifier.shapeKind(v.input).wire
            if (actual == v.expectedWire) null else "'${v.input}' (${v.note}): expected ${v.expectedWire}, got $actual"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the ADR's named vectors are present in the shared list`() {
        val byInput = KindClassifierVectors.VECTORS.associate { it.input to it.expectedWire }
        mapOf(
            "\$45.66" to "mixed",
            "4821" to "digits",
            "Pickup & delivery" to "words:2",
            "O'Brien" to "words:1",
            "Drop-off" to "words:1",
            "→" to "mixed",
            "aa bb cc dd ee ff gg hh ii" to "words:8+",
            "Apt 12" to "mixed",
        ).forEach { (input, wire) -> assertEquals(input, wire, byInput[input]) }
    }

    @Test
    fun `only words 1 to 8 are hashable`() {
        assertTrue(KindClassifier.shapeKind("Accept").hashable)
        assertTrue(KindClassifier.shapeKind("aa bb cc dd ee ff gg hh").hashable)
        assertFalse(KindClassifier.shapeKind("aa bb cc dd ee ff gg hh ii").hashable)
        assertFalse(KindClassifier.shapeKind("4821").hashable)
        assertFalse(KindClassifier.shapeKind("\$45.66").hashable)
    }

    @Test
    fun `wire kind predicates accept exactly the grammar's vocabulary`() {
        listOf("withheld", "digits", "mixed", "words:8+", "words:1", "words:8").forEach {
            assertTrue(it, KindClassifier.isWireKind(it))
        }
        listOf("words:0", "words:9", "words:10", "words:", "words:1a", "WITHHELD", "text", "").forEach {
            assertFalse(it, KindClassifier.isWireKind(it))
        }
        (1..8).forEach { assertTrue(KindClassifier.isHashableKind("words:$it")) }
        listOf("words:8+", "withheld", "digits", "mixed").forEach { assertFalse(it, KindClassifier.isHashableKind(it)) }
    }

    @Test
    fun `a blank value is never words or digits`() {
        assertEquals("mixed", KindClassifier.shapeKind("   ").wire)
        assertEquals("mixed", KindClassifier.shapeKind("").wire)
    }
}
