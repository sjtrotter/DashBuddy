package cloud.trotter.dashbuddy.core.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #548 structural invariant: a PICKUP-phase recognition rule must NEVER parse a customer identity.
 *
 * A pickup belongs to a STORE, not a customer; only DROPOFF rules hash the customer (the
 * customers-are-hashed pledge applies on the drop side). The 06-20 Peng's stack bug was a
 * multi-node `pickup_arrival` screen whose `customerNameHash` parse bled the *other* drop's
 * customer hash onto the PICKUP task — a customer identity a pickup has no business owning.
 *
 * The fix removed that parse; this test is the regression guard against any future rule edit
 * silently re-adding a customer field to a PICKUP parse. It reads the production rule JSON (the
 * SSOT producer) directly, so it covers every rule and branch — not only the screens with corpus.
 */
class PickupNoCustomerIdentityTest {

    private val customerFields = listOf("customerNameHash", "customerAddressHash")

    @Test
    fun `no PICKUP-phase rule parses a customer identity (#548)`() {
        val offenders = mutableListOf<String>()
        File(TestRulesetFactory.rulesDir).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }
            ?.forEach { file ->
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                root["screens"]?.jsonArray?.forEach { collectOffenders(it.jsonObject, file.name, offenders) }
            }
        assertTrue(
            "A PICKUP-phase rule parsed a customer identity — pickups belong to a store, not a " +
                "customer (#548). Offenders:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun collectOffenders(rule: JsonObject, fileName: String, offenders: MutableList<String>) {
        val fields = rule["parse"]?.jsonObject?.get("fields")?.jsonObject
        if (fields?.get("phase")?.jsonPrimitive?.contentOrNull == "PICKUP") {
            val id = rule["id"]?.jsonPrimitive?.contentOrNull ?: "(no id)"
            customerFields.filter { fields.containsKey(it) }
                .forEach { offenders += "$fileName :: $id parses '$it' on a PICKUP-phase parse" }
        }
        rule["branches"]?.jsonArray?.forEach { collectOffenders(it.jsonObject, fileName, offenders) }
    }
}
