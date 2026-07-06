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
 * #548 structural invariant: a PICKUP-phase recognition rule must NEVER parse a customer identity —
 * with ONE narrow, deliberate exception (#526 D6a, below).
 *
 * A pickup belongs to a STORE, not a customer; the customer is hashed on the DROP side. The 06-20
 * Peng's stack bug was a multi-node `pickup_arrival` screen whose `customerNameHash` parse bled the
 * *other* drop's customer hash onto the PICKUP task from an AMBIGUOUS node (`instructions_title`).
 *
 * #526 D6a EXCEPTION — the arrival's "Order for <name>" customer, from the SPECIFIC, redacted
 * `customer_name` node: `doordash.screen.pickup_arrival` may parse `customerNameHash` so a
 * multi-store stack's dropoff store can be re-attributed by a customer-hash join to its pickup
 * lineage (the F2 "Unknown store" $0 row). This is bounded-safe by construction: the hash is
 * `sha256`'d at the edge and redacted (never plaintext), and the join ([reconcileDropoffStore])
 * requires an EXACTLY-ONE-match, falling through when a mixed stack makes it ambiguous — so a
 * last-seen/wrong pickup hash can never mis-attribute a store. `customerAddressHash` stays forbidden
 * on every pickup rule (never needed), and `customerNameHash` stays forbidden on every pickup rule
 * OTHER than the arrival surface (the ambiguous-node bleed the original guard was cut for).
 *
 * This test reads the production rule JSON (the SSOT producer) directly, so it covers every rule and
 * branch — not only the screens with corpus.
 */
class PickupNoCustomerIdentityTest {

    private val customerFields = listOf("customerNameHash", "customerAddressHash")

    /** #526 D6a: the single rule id allowed to parse `customerNameHash` on a PICKUP phase. */
    private val nameHashAllowedRuleId = "doordash.screen.pickup_arrival"

    @Test
    fun `no PICKUP-phase rule parses a customer identity (#548)`() {
        val offenders = mutableListOf<String>()
        File(TestRulesetFactory.rulesDir).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }
            ?.forEach { file ->
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                // Scan every rule section that can carry a parse block (screens today; notifications/
                // clicks defensively) so a PICKUP customer parse can't slip in via any surface.
                for (section in listOf("screens", "notifications", "clicks")) {
                    root[section]?.jsonArray?.forEach { collectOffenders(it.jsonObject, file.name, offenders) }
                }
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
                // #526 D6a: pickup_arrival is allowed to parse customerNameHash (the join enabler);
                // customerAddressHash and every other pickup rule stay forbidden.
                .filterNot { it == "customerNameHash" && id == nameHashAllowedRuleId }
                .forEach { offenders += "$fileName :: $id parses '$it' on a PICKUP-phase parse" }
        }
        rule["branches"]?.jsonArray?.forEach { collectOffenders(it.jsonObject, fileName, offenders) }
    }
}
