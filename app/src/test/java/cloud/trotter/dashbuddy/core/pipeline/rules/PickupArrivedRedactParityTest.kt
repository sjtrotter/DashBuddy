package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers.IdentityKind
import cloud.trotter.dashbuddy.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.WHOLE_MASK_HEX
import cloud.trotter.dashbuddy.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.hexIn
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PickupArrivedRedactParityTest {

    private val screens by lazy {
        Json.parseToJsonElement(File(TestRulesetFactory.rulesDir, "doordash.json").readText())
            .jsonObject.getValue("screens").jsonArray.map { it.jsonObject }
    }

    /**
     * The rule's redact entries for a suffix with their `comment` key dropped — comments survive
     * canonicalization into the generated asset, and a receipt on one copy must not read as drift.
     */
    private fun nameIdEntries(rule: JsonObject, suffix: String): List<JsonElement> =
        rule["redact"]?.jsonArray.orEmpty().filter {
            it.jsonObject["find"]?.jsonObject?.get("hasIdSuffix")?.jsonPrimitive?.content == suffix
        }.map { JsonObject(it.jsonObject.filterKeys { key -> key != "comment" }) }

    private val nameSuffixes = CustomerTextMarkers.ID_MARKER_TABLE
        .filter { it.kind == IdentityKind.NAME }.map { it.suffix }

    private val references: Map<String, JsonElement> by lazy {
        val referenceRuleIds = mapOf(
            "customer_name" to "doordash.screen.pickup_arrival",
            "order_cx_name" to "doordash.screen.pickup_order_picker",
        )
        nameSuffixes.associateWith { suffix ->
            val referenceId = referenceRuleIds[suffix]
                ?: error("NAME id '$suffix' needs an explicit redact reference rule mapping")
            val rule = screens.single { it.getValue("id").jsonPrimitive.content == referenceId }
            val entries = nameIdEntries(rule, suffix)
            assertEquals("$referenceId must have exactly one $suffix redact reference", 1, entries.size)
            entries.single()
        }
    }

    private val ruleIdsCarryingReferences by lazy {
        File(TestRulesetFactory.rulesDir).walkTopDown()
            .filter { it.isFile && it.extension == "json" }
            .sortedBy { it.path }
            .flatMap { file ->
                Json.parseToJsonElement(file.readText()).jsonObject["screens"]?.jsonArray.orEmpty()
                    .asSequence().map { it.jsonObject }
            }
            .filter { rule -> references.all { (suffix, reference) -> reference in nameIdEntries(rule, suffix) } }
            .map { it.getValue("id").jsonPrimitive.content }.toSet()
    }

    private fun declaresArrival(ruleOrBranch: JsonObject): Boolean =
        ruleOrBranch["state"]?.jsonObject?.get("flow")?.jsonPrimitive?.content == "task:pickup:arrived"

    /** #1309: every DoorDash pickup-arrived rule must carry each NAME id's reference redact entry. */
    @Test
    fun `pickup arrived rules keep generated customer name redact parity`() {
        val arrivedRules = screens.filter { rule ->
            declaresArrival(rule) || rule["branches"]?.jsonArray.orEmpty().any {
                declaresArrival(it.jsonObject)
            }
        }
        assertTrue("generated rules must include pickup-arrived screens", arrivedRules.isNotEmpty())
        val arrivedIds = arrivedRules.map { it.getValue("id").jsonPrimitive.content }.toSet()
        for (id in listOf(
            "doordash.screen.pickup_arrival",
            "doordash.screen.pickup_wait_survey",
            "doordash.screen.pickup_picked_up",
            "doordash.screen.pickup_verify",
            "doordash.screen.pickup_steps",
        )) {
            assertTrue("#1309 pickup-arrived coverage must include $id", id in arrivedIds)
        }

        for (rule in arrivedRules) {
            val id = rule.getValue("id").jsonPrimitive.content
            for ((suffix, reference) in references) {
                val entries = nameIdEntries(rule, suffix)
                assertEquals("$id must carry exactly one $suffix redact entry", 1, entries.size)
                assertEquals("$id must structurally match $suffix's reference redact entry", reference, entries.single())
            }
        }
        for ((suffix, reference) in references) {
            assertEquals("$suffix must normalize customer names", "customerName", reference.jsonObject["normalize"]?.jsonPrimitive?.content)
            assertFalse("$suffix must retain the customer's hex", reference.jsonObject.containsKey("plainMask"))
        }
    }

    /** #1309: every claimed NAME-id frame must use a rule carrying all NAME reference entries, on any platform. */
    @Test
    fun `corpus customer name frames are claimed by rules carrying the reference redact`() {
        val base = File("src/test/resources/snapshots")
        // INBOX and the UNKNOWN staging root are gitignored traffic; SENSITIVE frames are dropped at the
        // content gate before any envelope exists, so no redact ever runs on them. `UNKNOWN/negative`
        // IS committed corpus (#941) and stays in the walk: a customer-bearing negative frame must stay
        // UNKNOWN (the backstop's territory), and if a rule ever claims one, that rule must mask it.
        val negativeDir = File(base, "UNKNOWN/negative")
        val fixtures = base.walkTopDown()
            .onEnter { dir ->
                dir.name !in setOf("INBOX", "SENSITIVE")
            }
            .filter { it.isFile && it.extension == "json" && it.name != "approved-parse-output.json" }
            .filterNot { it.parentFile == File(base, "UNKNOWN") } // the staging root's own files, never its committed negative/ child
            .sortedBy { it.path }
        val coveredRuleIds = ruleIdsCarryingReferences
        val violations = mutableListOf<String>()
        var framesWithNameId = 0

        for (file in fixtures) {
            val path = file.relativeTo(base).invariantSeparatorsPath
            // Click envelopes (`click_context.v1`, whose payload wraps a node + screenTarget) and the
            // notification-census fixtures (`notification.v1`) are not screen trees. Any OTHER schema is
            // a violation, never a skip (Astra r1): a renamed screen schema must not vanish from coverage.
            when (val schema = schemaIdOf(file)) {
                null, SCREEN_SCHEMA -> Unit
                in NON_SCREEN_SCHEMAS -> continue
                else -> { violations += "$path: unexpected schemaId '$schema' (add it to NON_SCREEN_SCHEMAS only if it is not a screen tree)"; continue }
            }
            val tree = try {
                TestResourceLoader.loadNode(file)
            } catch (e: Exception) {
                violations += "$path: loadNode failed: ${e.message}; cause: ${e.cause?.message}"
                continue
            }
            if (!carriesNameId(tree)) continue
            framesWithNameId++
            try {
                val ruleId = TestRulesetFactory.screenRuleset.matchFirst(tree)?.ruleId
                val isNegative = file.absoluteFile.startsWith(negativeDir.absoluteFile)
                when {
                    ruleId == null && isNegative -> Unit // the negative corpus must stay UNKNOWN; the backstop owns it
                    ruleId == null -> violations += "$path: NAME-id frame is unclaimed"
                    ruleId !in coveredRuleIds ->
                        violations += "$path: $ruleId does not carry all NAME-id reference redact entries"
                }
            } catch (e: Exception) {
                violations += "$path: matchFirst failed: ${e.message}"
            }
        }
        if (framesWithNameId < 20) {
            violations += "expected at least 20 NAME-id frames, saw $framesWithNameId"
        }
        assertTrue("#1309 corpus violations:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    private fun carriesNameId(node: UiNode): Boolean =
        nameSuffixes.any { node.matchesId(it) } || node.children.any { carriesNameId(it) }

    private fun schemaIdOf(file: File): String? = runCatching {
        Json.parseToJsonElement(file.readText()).jsonObject["schemaId"]?.jsonPrimitive?.content
    }.getOrNull()

    private companion object {
        const val SCREEN_SCHEMA = "uinode.v1"
        val NON_SCREEN_SCHEMAS = setOf("click_context.v1", "notification.v1")
    }

    /**
     * #1309: confirm-pickup, split-label verify and bin-scan shapes must recognize and mask to their reference hexes.
     * Merchant item names and options must survive, while the raw customer name must never serialize.
     */
    @Test
    fun `picked up verify and bin scan frames mask customer names with reference hexes and preserve items`() {
        val name = "Marisol Q"
        val itemName = "Spicy Chicken Sandwich"
        val itemInstructions = "Size Choice: Large"
        fun idNode(id: String, text: String) =
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/$id", text = text)

        val pickedUp = UiNode(children = listOf(
            UiNode(text = "Confirm pickup"),
            UiNode(text = "Loading…"),
            idNode("customer_name", name),
            idNode("order_item_name", itemName),
            idNode("order_item_instructions", itemInstructions),
        )).restoreParents()
        val verify = UiNode(children = listOf(
            UiNode(text = "Verify order"),
            idNode("customer_name_label", "Order for"),
            idNode("customer_name", name),
            // Neither shape has the prism button title required by pickup_arrival.
            UiNode(text = "Confirm pickup"),
            UiNode(text = "Can't verify order"),
            UiNode(children = listOf(
                idNode("order_item_name", itemName),
                idNode("order_item_instructions", itemInstructions),
            )),
        )).restoreParents()
        val ruleset = TestRulesetFactory.screenRuleset
        val pickupCardHex = hexIn(
            ruleset.ruleById("doordash.screen.pickup_arrival")!!.redact.apply(
                idNode("customer_name", name),
            ).text!!,
            "the pickup card's customer_name",
        )

        for ((ruleId, tree) in listOf(
            "doordash.screen.pickup_picked_up" to pickedUp,
            "doordash.screen.pickup_verify" to verify,
        )) {
            val actualRuleId = ruleset.matchFirst(tree)?.ruleId
            assertEquals("$ruleId synthetic frame was claimed by $actualRuleId", ruleId, actualRuleId)
            val redacted = ruleset.ruleById(ruleId)!!.redact.apply(tree)
            val maskedName = redacted.findDescendantById("customer_name")?.text.orEmpty()
            assertTrue("$ruleId must mask the whole customer_name; got '$maskedName'", WHOLE_MASK_HEX.matches(maskedName))
            assertEquals("$ruleId must use the pickup card's hex", pickupCardHex, hexIn(maskedName, "$ruleId customer_name"))
            assertEquals("$ruleId must preserve item names", itemName, redacted.findDescendantById("order_item_name")?.text)
            assertEquals(
                "$ruleId must preserve item options",
                itemInstructions,
                redacted.findDescendantById("order_item_instructions")?.text,
            )
            assertFalse("$ruleId must not serialize the raw customer name", UiNodeSchema.serialize(redacted).contains(name))
        }

        val binScanNames = listOf(name, "Devin R")
        val binScan = UiNode(children = listOf(
            UiNode(text = "Pickup steps"),
            UiNode(text = "Scan barcodes on 2 items"),
            UiNode(text = "Complete pickup"),
            idNode("order_cx_name", binScanNames[0]),
            idNode("order_cx_name", binScanNames[1]),
            idNode("order_item_name", itemName),
        )).restoreParents()
        val binScanRuleId = "doordash.screen.pickup_steps"
        val binScanMatch = ruleset.matchFirst(binScan)
        assertEquals("bin-scan synthetic frame must classify as pickup_steps", binScanRuleId, binScanMatch?.ruleId)
        // The first (receipt-photo / confirm-pickup) branch is flowless: pin the bin-scan branch too.
        assertEquals("bin-scan must select the arrived branch", Flow.TaskPickupArrived, binScanMatch?.flow)
        val redacted = ruleset.ruleById(binScanRuleId)!!.redact.apply(binScan)
        val maskedNames = redacted.children.filter { it.matchesId("order_cx_name") }.map { it.text.orEmpty() }
        assertEquals("bin-scan must retain both customer nodes", binScanNames.size, maskedNames.size)
        val orderPicker = ruleset.ruleById("doordash.screen.pickup_order_picker")!!
        val hexes = binScanNames.zip(maskedNames).map { (rawName, maskedName) ->
            assertTrue("bin-scan must mask the whole order_cx_name; got '$maskedName'", WHOLE_MASK_HEX.matches(maskedName))
            val expectedHex = hexIn(
                orderPicker.redact.apply(idNode("order_cx_name", rawName)).text!!,
                "the order picker's order_cx_name",
            )
            val actualHex = hexIn(maskedName, "bin-scan order_cx_name")
            assertEquals("bin-scan must use the order picker's hex", expectedHex, actualHex)
            actualHex
        }
        assertEquals("different bin-scan customers must retain distinct hexes", binScanNames.size, hexes.toSet().size)
        assertEquals("bin-scan must preserve the item name", itemName, redacted.findDescendantById("order_item_name")?.text)
        val serialized = UiNodeSchema.serialize(redacted)
        for (rawName in binScanNames) {
            assertFalse("bin-scan must not serialize '$rawName'", serialized.contains(rawName))
        }
    }
}
