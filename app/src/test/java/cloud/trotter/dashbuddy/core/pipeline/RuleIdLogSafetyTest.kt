package cloud.trotter.dashbuddy.core.pipeline

import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #862 review LOW-2 — the recognized-frame backstop WARNs interpolate `obs.ruleId`, so a rule id
 * is the ONE non-constant token in those lines. #862 made the marker itself non-verbatim
 * ([MarkerLogId]); a future rule id that happened to contain a sensitive keyword (`expiry`,
 * `visa`, `crimson`, … — a spaceless keyword is the realistic risk, since ids carry no spaces)
 * would silently re-introduce the self-scrub the fix removed: the shareable-log sink would redact
 * the whole diagnostic again, and nothing else would notice.
 *
 * This guard reads the SAME canonicalized rule assets [TestRulesetFactory] compiles (the
 * `matchers/rules` JSON5 sources → `:core:pipeline:importMatchersRules` → generated
 * `assets/rules` JSON) and asserts every **reachable** rule id is
 * [SensitiveTextMarkers.findMarker]-clean.
 *
 * ### Why only part of the population
 *
 * The two ruleId-bearing templates live on the RECOGNIZED arms of
 * `CaptureWriter.captureScreen` / `captureNotification`, so the reachable population is
 * **non-sensitive screen + notification rule ids**:
 *  - **sensitive rules** (`parse.as == "sensitive"`) are unreachable there — they are priority-0,
 *    non-overrideable, and the pipeline's sensitive gate drops the frame before any capture is
 *    written, so their id can never be interpolated into a WARN. They are excluded deliberately
 *    rather than silently: a sensitive rule id is *allowed* to name a keyword (many describe one).
 *  - **click rules** never reach a ruleId-bearing template at all — the click customer backstop
 *    runs only on UNKNOWN clicks (`ruleId == null`), and its message carries no ruleId.
 */
class RuleIdLogSafetyTest {

    private data class RuleId(val id: String, val file: String, val section: String, val sensitive: Boolean)

    private fun readRuleIds(): List<RuleId> {
        val out = mutableListOf<RuleId>()
        File(TestRulesetFactory.rulesDir).listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }
            ?.forEach { file ->
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                for (section in listOf("screens", "notifications")) {
                    for (ruleEl in root[section]?.jsonArray ?: continue) {
                        val rule = ruleEl.jsonObject
                        val id = rule["id"]?.jsonPrimitive?.contentOrNull ?: continue
                        val parseAs = rule["parse"]?.jsonObject?.get("as")?.jsonPrimitive?.contentOrNull
                        out += RuleId(id, file.name, section, sensitive = parseAs == "sensitive")
                    }
                }
            }
        return out
    }

    @Test
    fun `every reachable rule id is safe to log verbatim in a capture backstop WARN`() {
        val ids = readRuleIds()
        assertTrue("expected rules in the generated assets — fixture/wiring broken", ids.isNotEmpty())

        val reachable = ids.filterNot { it.sensitive }
        assertTrue("the non-sensitive population is empty — filter is wrong", reachable.isNotEmpty())

        for (rule in reachable) {
            assertNull(
                "rule id '${rule.id}' (${rule.file}/${rule.section}) carries a sensitive marker — " +
                    "the recognized-frame capture backstop WARN interpolates it, so this id would " +
                    "make that diagnostic self-scrub at the shareable-log sink (#862). Rename the rule.",
                SensitiveTextMarkers.findMarker(rule.id),
            )
        }
    }
}
