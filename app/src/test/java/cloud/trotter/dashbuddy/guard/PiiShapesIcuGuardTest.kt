package cloud.trotter.dashbuddy.guard

import cloud.trotter.dashbuddy.domain.census.contract.ClassNameGrammar
import cloud.trotter.dashbuddy.domain.census.contract.ResourceIdGrammar
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1160 review CC4 — the ICU bare-`}` rule applied to every COMPILED regex `PiiShapes` exposes.
 *
 * [IcuRegexGuardTest] scans source `Regex("literal")` sites, so it cannot see a pattern built from a
 * `const` reference (`FIRST_LAST_INITIAL*`) or a `+` concatenation (`STREET`, `CARD`). A bare `}` in
 * one of those compiles green on the host JVM and throws `ExceptionInInitializerError` inside
 * `PiiShapes` on ART — `Refusal.BUILD_FAILED` on every census frame forever (the #909 class). This test
 * reads each live `Regex.pattern` and applies the SAME checker ([IcuRegexGuardTest.bareClosingBraceIndex],
 * the SSOT — not a copy).
 */
class PiiShapesIcuGuardTest {

    private val checker = IcuRegexGuardTest()

    /** Every `Regex` field on [owner], via reflection, so a newly added constant is covered automatically. */
    private fun regexFields(owner: Any): Map<String, Regex> =
        owner.javaClass.declaredFields
            .filter { Regex::class.java.isAssignableFrom(it.type) }
            .associate { f -> f.isAccessible = true; "${owner.javaClass.simpleName}.${f.name}" to (f.get(owner) as Regex) }

    @Test
    fun `no compiled PiiShapes or census-grammar regex carries a bare closing brace`() {
        val patterns = regexFields(PiiShapes) + regexFields(ResourceIdGrammar) + regexFields(ClassNameGrammar) +
            PiiShapes.VALUE_SHAPES.associate { "VALUE_SHAPES.${it.name}" to it.regex }
        // Reflection must see the known constants (guards the guard).
        listOf("PHONE", "STREET", "CARD", "FIRST_LAST_INITIAL", "FIRST_LAST_INITIAL_EMBEDDED_REGEX").forEach {
            assertTrue("reflection missed PiiShapes.$it", "PiiShapes.$it" in patterns)
        }
        val problems = patterns.mapNotNull { (name, regex) ->
            val at = checker.bareClosingBraceIndex(regex.pattern)
            if (at >= 0) "$name: bare '}' at $at in `${regex.pattern}`" else null
        }
        assertEquals(emptyList<String>(), problems)
    }
}
