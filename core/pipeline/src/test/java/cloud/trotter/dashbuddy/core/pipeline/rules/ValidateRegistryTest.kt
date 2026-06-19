package cloud.trotter.dashbuddy.core.pipeline.rules

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Direct unit coverage for [ValidateRegistry] — the validate-assertion vocabulary
 * split out of [TransformRegistry] (audit #13).
 *
 * Prior to the split these assertions were only exercised indirectly (through the
 * RuleCompiler + Ruleset match path). These tests pin each assertion's outcome
 * mapping (Pass / Skip / DropParsed) and the fail-fast on an unknown name.
 */
class ValidateRegistryTest {

    private fun args(json: String) = Json.parseToJsonElement(json).jsonObject

    // ── Dispatch / name validation ──────────────────────────────────────────

    @Test
    fun `validateAssertionName accepts every known assertion`() {
        listOf("sumApproxEquals", "fieldsLe", "fieldsGe", "fieldNotNull", "fieldEquals")
            .forEach { ValidateRegistry.validateAssertionName(it) }
    }

    @Test
    fun `validateAssertionName rejects an unknown assertion`() {
        val ex = assertThrows(RuleCompileException::class.java) {
            ValidateRegistry.validateAssertionName("notAnAssertion")
        }
        assertEquals(true, ex.message?.contains("notAnAssertion"))
    }

    @Test
    fun `validate throws on an unknown assertion name`() {
        assertThrows(RuleCompileException::class.java) {
            ValidateRegistry.validate("notAnAssertion", args("""{}"""), emptyMap())
        }
    }

    // ── sumApproxEquals ─────────────────────────────────────────────────────

    @Test
    fun `sumApproxEquals passes within tolerance`() {
        val out = ValidateRegistry.validate(
            "sumApproxEquals",
            args("""{ "fields": ["a","b"], "target": "t", "tolerance": 0.02 }"""),
            mapOf("a" to 4.0, "b" to 6.0, "t" to 10.01),
        )
        assertEquals(ValidateOutcome.Pass, out)
    }

    @Test
    fun `sumApproxEquals skips outside tolerance`() {
        val out = ValidateRegistry.validate(
            "sumApproxEquals",
            args("""{ "fields": ["a","b"], "target": "t", "tolerance": 0.02 }"""),
            mapOf("a" to 4.0, "b" to 6.0, "t" to 11.0),
        )
        assertEquals(ValidateOutcome.Skip, out)
    }

    @Test
    fun `sumApproxEquals passes (no-op) when a field is missing`() {
        // Missing inputs can't be checked, so the assertion is a no-op (Pass).
        val out = ValidateRegistry.validate(
            "sumApproxEquals",
            args("""{ "fields": ["a","b"], "target": "t" }"""),
            mapOf("a" to 4.0, "t" to 10.0),
        )
        assertEquals(ValidateOutcome.Pass, out)
    }

    // ── fieldsLe / fieldsGe ─────────────────────────────────────────────────

    @Test
    fun `fieldsLe passes when within bound and drops parsed when violated`() {
        val pass = ValidateRegistry.validate(
            "fieldsLe",
            args("""{ "field": "a", "le": "b" }"""),
            mapOf("a" to 5.0, "b" to 10.0),
        )
        assertEquals(ValidateOutcome.Pass, pass)

        val drop = ValidateRegistry.validate(
            "fieldsLe",
            args("""{ "field": "a", "le": "b" }"""),
            mapOf("a" to 15.0, "b" to 10.0),
        )
        assertEquals(ValidateOutcome.DropParsed, drop)
    }

    @Test
    fun `fieldsGe passes when within bound and drops parsed when violated`() {
        val pass = ValidateRegistry.validate(
            "fieldsGe",
            args("""{ "field": "a", "ge": "b" }"""),
            mapOf("a" to 12.0, "b" to 10.0),
        )
        assertEquals(ValidateOutcome.Pass, pass)

        val drop = ValidateRegistry.validate(
            "fieldsGe",
            args("""{ "field": "a", "ge": "b" }"""),
            mapOf("a" to 5.0, "b" to 10.0),
        )
        assertEquals(ValidateOutcome.DropParsed, drop)
    }

    // ── fieldNotNull ────────────────────────────────────────────────────────

    @Test
    fun `fieldNotNull passes when present, skips when absent`() {
        val present = ValidateRegistry.validate(
            "fieldNotNull",
            args("""{ "field": "f" }"""),
            mapOf("f" to "value"),
        )
        assertEquals(ValidateOutcome.Pass, present)

        val absent = ValidateRegistry.validate(
            "fieldNotNull",
            args("""{ "field": "f" }"""),
            mapOf("f" to null),
        )
        assertEquals(ValidateOutcome.Skip, absent)
    }

    // ── fieldEquals ─────────────────────────────────────────────────────────

    @Test
    fun `fieldEquals matches a string value`() {
        val out = ValidateRegistry.validate(
            "fieldEquals",
            args("""{ "field": "f", "value": "accept" }"""),
            mapOf("f" to "accept"),
        )
        assertEquals(ValidateOutcome.Pass, out)
    }

    @Test
    fun `fieldEquals matches a numeric value`() {
        val out = ValidateRegistry.validate(
            "fieldEquals",
            args("""{ "field": "n", "value": 3 }"""),
            mapOf("n" to 3),
        )
        assertEquals(ValidateOutcome.Pass, out)
    }

    @Test
    fun `fieldEquals matches a boolean value`() {
        val out = ValidateRegistry.validate(
            "fieldEquals",
            args("""{ "field": "b", "value": true }"""),
            mapOf("b" to true),
        )
        assertEquals(ValidateOutcome.Pass, out)
    }

    @Test
    fun `fieldEquals skips on mismatch`() {
        val out = ValidateRegistry.validate(
            "fieldEquals",
            args("""{ "field": "f", "value": "accept" }"""),
            mapOf("f" to "decline"),
        )
        assertEquals(ValidateOutcome.Skip, out)
    }
}
