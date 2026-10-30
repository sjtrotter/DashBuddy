package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Branch 0 (8.99.20, #1141) is a strict subset of branch 1 (#1306), kept as its own branch
 * at the developer's request for the old version. This generated-asset pin holds their parse/bind
 * copies in lockstep (CLAUDE.md principle 5; the [DropoffBannerRedactParityTest] pattern) and
 * pins the structural anchor so a later "helpful" survey-text conjunct cannot creep back in.
 */
class PickupWaitArrivalBranchParityTest {

    @Test
    fun `arrival branches keep parse and bind parity with a structural at-store anchor (#1306)`() {
        val file = File(File(TestRulesetFactory.rulesDir), "doordash.json")
        val screens = Json.parseToJsonElement(file.readText()).jsonObject.getValue("screens").jsonArray
        val rule = screens.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == "doordash.screen.pickup_wait_survey"
        }.jsonObject
        val branches = rule.getValue("branches").jsonArray
        assertTrue("the legacy and at-store arrival branches must both exist", branches.size >= 2)
        val legacy = branches[0].jsonObject
        val atStore = branches[1].jsonObject

        assertEquals("arrival parse copies must stay in lockstep", legacy.getValue("parse"), atStore.getValue("parse"))
        assertEquals("arrival bind copies must stay in lockstep", legacy.getValue("bind"), atStore.getValue("bind"))
        listOf(legacy, atStore).forEachIndexed { index, branch ->
            assertEquals(
                "branch $index must declare pickup arrival",
                "task:pickup:arrived",
                branch.getValue("state").jsonObject.getValue("flow").jsonPrimitive.content,
            )
        }

        val require = atStore.getValue("require").toString()
        for (text in listOf("curbside_pickup_instructions_component_view", "Merchant notified")) {
            assertFalse("branch 1 must not require legacy arrival chrome: $text", require.contains(text))
        }
        for (id in listOf("at_store_contact_view", "mx_contact_view", "pickup_survey_view")) {
            assertTrue("branch 1 must retain its structural anchor: $id", require.contains(id))
        }

        assertTrue("#1222 rejects must remain hoisted to the rule", rule.containsKey("reject"))
        branches.forEachIndexed { index, branch ->
            assertFalse("branch $index must not have its own reject", branch.jsonObject.containsKey("reject"))
        }
    }
}
