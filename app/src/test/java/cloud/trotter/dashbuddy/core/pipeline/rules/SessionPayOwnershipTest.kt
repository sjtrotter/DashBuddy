package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Exactly ONE rule may read the on-dash earnings pill as the session's running total (#1029).
 *
 * The pill (`earnings_pill`) is an animated digit-wheel with a label beside it, and the SAME view
 * id renders **two different quantities**: "This dash" on the waiting-for-offer surface, and
 * "This week" on the idle map / side-nav / on-dash map. A weekly total written into
 * `Session.runningEarnings` would be a large, plausible, STABLE lie — the settle gate is designed
 * to commit exactly that kind of repeated value, so it offers no protection here. The defences are
 * (a) the `hasAnyText: "This dash"` conjunct inside the one rule that reads it, and (b) this test,
 * which keeps a second rule from quietly acquiring a `sessionPay` field.
 *
 * Scans the CANONICAL generated ruleset rather than the JSON5 source, so a rule arriving through a
 * different sub-file — or, later, a different source entirely (#192) — is still in scope.
 */
class SessionPayOwnershipTest {

    private companion object {
        /** The field that feeds `Session.runningEarnings` through the idle shape. */
        const val FIELD = "sessionPay"
    }

    /**
     * Every `parse.fields` block a screen rule can carry — the top-level one AND each BRANCH's
     * own (`docs/rules.schema.json` `branchObject` allows a per-branch parse; `uber.screen.offer`
     * uses one). Scanning only the top level would let a branch-level `sessionPay` — reading the
     * "This week" pill on some other surface — walk straight past this pin.
     */
    private fun parseFieldSets(rule: JsonObject): List<JsonObject> = buildList {
        rule["parse"]?.jsonObject?.get("fields")?.jsonObject?.let(::add)
        rule["branches"]?.jsonArray.orEmpty().forEach { branch ->
            branch.jsonObject["parse"]?.jsonObject?.get("fields")?.jsonObject?.let(::add)
        }
    }

    private fun screenRulesDeclaring(field: String): List<String> =
        File(TestRulesetFactory.rulesDir)
            .listFiles { f -> f.extension == "json" }
            ?.sortedBy { it.name }
            ?.flatMap { file ->
                Json.parseToJsonElement(file.readText()).jsonObject["screens"]
                    ?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .filter { rule -> parseFieldSets(rule).any { it.containsKey(field) } }
                    .mapNotNull { it["id"]?.toString()?.trim('"') }
            }
            ?: emptyList()

    @Test
    fun `only the waiting-for-offer rule parses sessionPay`() {
        assertEquals(
            "A second rule declaring '$FIELD' is how a WEEK total reaches the dash's running " +
                "earnings: `earnings_pill` renders 'This week' on the idle map, the side-nav " +
                "drawer and the on-dash map. If a new surface really does show the DASH total, " +
                "give it the same `hasAnyText: \"This dash\"` conjunct and add it here " +
                "deliberately.",
            listOf("doordash.screen.waiting_for_offer"),
            screenRulesDeclaring(FIELD),
        )
    }
}
