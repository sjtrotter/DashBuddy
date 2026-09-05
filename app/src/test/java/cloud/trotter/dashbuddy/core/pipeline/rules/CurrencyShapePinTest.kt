package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every rule-declared money scan uses the ONE currency shape (#1029 E3).
 *
 * `parseGlyphCurrency` (Kotlin) and `nextSiblingMatchingRegex(...)` (rule JSON) both have to answer
 * "is this node a well-formed currency figure", and they were two hand-written patterns that were
 * loose in DIFFERENT ways — the Kotlin one took `$016.70`, the rule-side `^\$[\d,]+\.\d{2}$` took
 * `$,.00` (→ 0.0) and `$1,2,3.45`. [CurrencyShape] is now their single definition, and this pins
 * the rules to it byte-for-byte: the `SnapshotRedactor.FIRST_LAST_INITIAL_PATTERN` precedent, where
 * a shape shared between Kotlin and rule data is PINNED rather than trusted to stay in sync.
 *
 * Scans the CANONICAL generated rulesets, so a scan arriving through a different sub-file — or,
 * later, a different source entirely (#192) — is still in scope.
 */
class CurrencyShapePinTest {

    private companion object {
        const val NAV_PREFIX = "nextSiblingMatchingRegex("
    }

    /** `<ruleFile>:<pattern>` for every money scan declared anywhere in the generated assets. */
    private fun declaredScanPatterns(): List<Pair<String, String>> =
        File(TestRulesetFactory.rulesDir)
            .listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }
            ?.flatMap { file ->
                val found = mutableListOf<Pair<String, String>>()
                collectNavigates(Json.parseToJsonElement(file.readText()), found, file.name)
                found
            }
            ?: emptyList()

    private fun collectNavigates(
        element: JsonElement,
        into: MutableList<Pair<String, String>>,
        fileName: String,
    ) {
        when (element) {
            is JsonObject -> {
                val nav = element["navigate"]?.jsonPrimitive?.contentOrNullSafe()
                if (nav != null && nav.startsWith(NAV_PREFIX)) {
                    // Strip the verb and the trailing `, <cap>` — the CAP is the row's own width,
                    // it is the PATTERN that must be identical everywhere.
                    val arg = nav.removePrefix(NAV_PREFIX).removeSuffix(")")
                    into += fileName to arg.replace(Regex(",\\s*\\d+$"), "")
                }
                element.values.forEach { collectNavigates(it, into, fileName) }
            }
            is kotlinx.serialization.json.JsonArray ->
                element.forEach { collectNavigates(it, into, fileName) }
            else -> Unit
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (isString) content else null

    @Test
    fun `every money scan declares the shared currency shape verbatim`() {
        val scans = declaredScanPatterns()
        assertTrue(
            "the pin is worthless if no rule declares a scan — the DoorDash receipt rules do",
            scans.isNotEmpty(),
        )
        for ((file, pattern) in scans) {
            assertEquals(
                "$file declares a hand-written currency pattern. Use CurrencyShape.RULE_PATTERN " +
                    "(json5-escaped) so the rule and `parseGlyphCurrency` can never drift into " +
                    "accepting different figures.",
                CurrencyShape.RULE_PATTERN,
                pattern,
            )
        }
    }

    @Test
    fun `the shared shape accepts and rejects the figures the transform does`() {
        val shape = Regex(CurrencyShape.RULE_PATTERN)
        for (good in listOf("\$0.00", "\$7.00", "\$16.70", "\$9999.99", "\$1,234.56")) {
            assertTrue(good, shape.matches(good))
        }
        for (bad in listOf("\$016.70", "\$1234,567.00", "\$12345.00", "\$,.00", "\$1,2,3.45", "799")) {
            assertTrue(bad, !shape.matches(bad))
        }
    }
}
