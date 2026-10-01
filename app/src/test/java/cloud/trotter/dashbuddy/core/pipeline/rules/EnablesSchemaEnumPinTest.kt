package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.action.RuleAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * #1167 review: the schema enum is a hand-maintained copy of the registry; this pin is the
 * one place the two can drift loudly (principle 5).
 */
class EnablesSchemaEnumPinTest {

    @Test
    fun `screen rule enables enum matches the action registry without duplicates`() {
        val schema = Json.parseToJsonElement(File("../docs/rules.schema.json").readText()).jsonObject
        val wires = schema.getValue("\$defs").jsonObject
            .getValue("screenRule").jsonObject
            .getValue("properties").jsonObject
            .getValue("enables").jsonObject
            .getValue("items").jsonObject
            .getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        val expected = RuleAction.entries.map { it.wire }.toSet()

        assertEquals("schema and action registry must stay in sync", expected, wires.toSet())
        assertEquals("schema enum must not contain duplicates", expected.size, wires.size)
    }
}
