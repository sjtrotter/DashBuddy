package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PR #1303 / #1121: a card rejected by offer_popup branch 1 but not given up by the drawer is
 * silently filed as chrome with no UNKNOWN capture.
 *
 * A rule file cannot reference another rule's predicate, so this test is the control against
 * drift under CLAUDE.md principle 5 (SSOT), following the [CurrencyShapePinTest] precedent.
 * Reads the GENERATED assets and pins the delta signatures byte-for-byte across both rejects.
 */
class DrawerOfferSignaturePinTest {

    private fun collectPatterns(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonObject -> {
                element["hasTextMatchesRegex"]?.jsonPrimitive?.content?.let { into += it }
                element.values.forEach { collectPatterns(it, into) }
            }
            is JsonArray -> element.forEach { collectPatterns(it, into) }
            else -> Unit
        }
    }

    @Test
    fun `every Compose TOTAL reject signature is also a drawer give-up signature`() {
        val file = File(File(TestRulesetFactory.rulesDir), "doordash.json")
        val screens = Json.parseToJsonElement(file.readText()).jsonObject.getValue("screens").jsonArray
        val drawer = screens.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == "doordash.screen.side_nav_drawer"
        }.jsonObject
        val offer = screens.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == "doordash.screen.offer_popup"
        }.jsonObject
        val drawerPatterns = mutableSetOf<String>()
        collectPatterns(drawer.getValue("reject"), drawerPatterns)
        val deltaPatterns = mutableSetOf<String>()
        collectPatterns(offer.getValue("branches").jsonArray[1].jsonObject.getValue("reject"), deltaPatterns)

        assertTrue("the pin is worthless if branch 1 rejects no signatures", deltaPatterns.isNotEmpty())
        assertTrue(
            "branch 1 rejects signatures the drawer still claims as chrome, losing UNKNOWN captures: " +
                (deltaPatterns - drawerPatterns),
            drawerPatterns.containsAll(deltaPatterns),
        )
        assertEquals("the drawer gives up on exactly four card signatures", 4, drawerPatterns.size)
        assertEquals(
            "two drawer signatures are delta signatures",
            2,
            drawerPatterns.count { it.startsWith("^\\+") },
        )
    }
}
