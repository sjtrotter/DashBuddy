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
 * #548 structural invariant: a PICKUP rule must NEVER parse a customer identity — with ONE narrow,
 * deliberate exception (#526 D6a, below).
 *
 * A pickup belongs to a STORE, not a customer; the customer is hashed on the DROP side. The 06-20
 * Peng's stack bug was a multi-node `pickup_arrival` screen whose `customerNameHash` parse bled the
 * *other* drop's customer hash onto the PICKUP task from an AMBIGUOUS node (`instructions_title`).
 *
 * #526 D6a EXCEPTION — the arrival's "Order for <name>" customer, from the SPECIFIC, redacted
 * `customer_name` node: `doordash.screen.pickup_arrival` may parse `customerNameHash` so a
 * multi-store stack's dropoff store can be re-attributed by a customer-hash join to its pickup
 * lineage (the F2 "Unknown store" $0 row). Bounded-safe by construction: the hash is `sha256`'d at
 * the edge and redacted (never plaintext), and the join ([reconcileDropoffStore]) requires an
 * EXACTLY-ONE-match. `customerAddressHash` stays forbidden on every pickup rule.
 *
 * #526 FIX6 hardening:
 *  - (a) NODE-PIN: the exception holds only while pickup_arrival's `customerNameHash` parse reads the
 *    redacted `customer_name` node — a future edit that re-points it at `instructions_title`
 *    (the exact 06-20 bleed vector) re-opens the offence.
 *  - (b) A rule/branch is scanned when its parse says `"phase": "PICKUP"` OR its (own or
 *    branch-inherited) `state.flow` starts with `task:pickup` — a parse that omits the phase literal
 *    no longer escapes the scan.
 *  - (c) The scan is asserted non-vacuous: ≥1 rules file was read AND the pickup_arrival carrier was
 *    actually seen (an empty/missing rules dir no longer passes silently).
 *
 * Reads the production rule JSON (the SSOT producer) directly, so it covers every rule and branch.
 */
class PickupNoCustomerIdentityTest {

    private val customerFields = listOf("customerNameHash", "customerAddressHash")

    /** #526 D6a: the single rule id allowed to parse `customerNameHash` on a PICKUP surface. */
    private val nameHashAllowedRuleId = "doordash.screen.pickup_arrival"

    @Test
    fun `no PICKUP rule parses a customer identity (#548)`() {
        val offenders = mutableListOf<String>()
        var filesScanned = 0
        var sawPickupArrival = false
        File(TestRulesetFactory.rulesDir).listFiles { f -> f.extension == "json" }?.sortedBy { it.name }
            ?.forEach { file ->
                filesScanned++
                val root = Json.parseToJsonElement(file.readText()).jsonObject
                // Scan every rule section that can carry a parse block (screens today; notifications/
                // clicks defensively) so a PICKUP customer parse can't slip in via any surface.
                for (section in listOf("screens", "notifications", "clicks")) {
                    root[section]?.jsonArray?.forEach {
                        if (scan(it.jsonObject, file.name, inheritedFlow = null, offenders)) sawPickupArrival = true
                    }
                }
            }
        assertTrue(
            "A PICKUP rule parsed a customer identity — pickups belong to a store, not a " +
                "customer (#548). Offenders:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
        // FIX6c: the scan must not pass vacuously.
        assertTrue("no rules files scanned — the generated ruleset dir is empty/missing", filesScanned >= 1)
        assertTrue(
            "the pickup_arrival rule (the D6a exception carrier) was never seen — the scan is likely " +
                "broken (wrong dir / structure drift)",
            sawPickupArrival,
        )
    }

    /** Returns true iff this subtree contained the [nameHashAllowedRuleId] rule (for the non-vacuous check). */
    private fun scan(rule: JsonObject, fileName: String, inheritedFlow: String?, offenders: MutableList<String>): Boolean {
        val id = rule["id"]?.jsonPrimitive?.contentOrNull
        // A branch inherits its parent's flow unless it overrides `state.flow` (FIX6b).
        val effectiveFlow = rule["state"]?.jsonObject?.get("flow")?.jsonPrimitive?.contentOrNull ?: inheritedFlow
        val fields = rule["parse"]?.jsonObject?.get("fields")?.jsonObject

        val phaseIsPickup = fields?.get("phase")?.jsonPrimitive?.contentOrNull == "PICKUP"
        val flowIsPickup = effectiveFlow?.startsWith("task:pickup") == true

        if (fields != null && (phaseIsPickup || flowIsPickup)) {
            val ruleId = id ?: "(no id)"
            customerFields.filter { fields.containsKey(it) }
                // FIX6a: the exception applies ONLY to pickup_arrival AND ONLY while its
                // customerNameHash parse still reads the redacted `customer_name` node.
                .filterNot { it == "customerNameHash" && ruleId == nameHashAllowedRuleId && customerNameNodePinned(fields) }
                .forEach {
                    offenders += "$fileName :: $ruleId parses '$it' on a PICKUP rule " +
                        "(phase=$phaseIsPickup, flow=$effectiveFlow)"
                }
        }

        var sawTarget = id == nameHashAllowedRuleId
        rule["branches"]?.jsonArray?.forEach {
            if (scan(it.jsonObject, fileName, effectiveFlow, offenders)) sawTarget = true
        }
        return sawTarget
    }

    /**
     * FIX6a: the D6a exception is valid only when pickup_arrival's `customerNameHash` parse targets the
     * `hasIdSuffix: "customer_name"` node (the redacted, unambiguous one) — never `instructions_title`,
     * the ambiguous node the original 06-20 bleed came from.
     */
    private fun customerNameNodePinned(fields: JsonObject): Boolean {
        val find = fields["customerNameHash"]?.jsonObject?.get("find")?.jsonObject ?: return false
        return find["hasIdSuffix"]?.jsonPrimitive?.contentOrNull == "customer_name"
    }
}
