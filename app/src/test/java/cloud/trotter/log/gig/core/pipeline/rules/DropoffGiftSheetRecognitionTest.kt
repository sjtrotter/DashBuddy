package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.core.pipeline.CustomerTextMarkers
import cloud.trotter.log.gig.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.WHOLE_MASK_HEX
import cloud.trotter.log.gig.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.hexIn
import cloud.trotter.log.gig.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.wholeMaskAfter
import cloud.trotter.log.gig.domain.capture.schema.UiNodeSchema
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Flow
import cloud.trotter.log.gig.test.util.TestResourceLoader
import cloud.trotter.log.gig.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1314 — the gift sender is the ordering customer; the recipient is masked, never parsed. */
class DropoffGiftSheetRecognitionTest {
    private val screens = TestRulesetFactory.screenRuleset
    private val ruleId = "doordash.screen.dropoff_pre_arrival"
    private val preArrival = screens.ruleById(ruleId)!!
    private val fixtures = listOf(
        "2026-10-11_11-55-36-426__doordash__accessibility.window__dropoff_pre_arrival__cf81b2.json",
        "2026-10-11_12-06-20-814__doordash__accessibility.window__dropoff_pre_arrival__08fcbf.json",
    )

    private fun load(name: String = fixtures.first()): UiNode = TestResourceLoader.loadNode(
        File("src/test/resources/snapshots/dropoff_pre_arrival", name),
    )

    private fun nodes(root: UiNode): List<UiNode> = listOf(root) + root.children.flatMap(::nodes)

    // NOT `hasId`: `UiNode.hasId` is a MEMBER that answers for the node OR ANY DESCENDANT, and a member wins
    // over an extension — every ancestor of the target would match (round-1 lesson). This is the node's OWN id.
    private fun UiNode.idIs(suffix: String): Boolean = matchesId(suffix)

    private fun node(root: UiNode, suffix: String): UiNode {
        val hits = nodes(root).filter { it.idIs(suffix) }
        require(hits.size == 1) {
            "expected exactly one :id/$suffix, got ${hits.size}: " +
                hits.map { "${it.viewIdResourceName}='${it.text}' parent=${it.parent?.viewIdResourceName}" } +
                " (tree has ${nodes(root).size} nodes)"
        }
        return hits.single()
    }

    private fun rewrite(root: UiNode, texts: Map<String, String>): UiNode = root.copy(
        text = texts.entries.firstOrNull { root.idIs(it.key) }?.value ?: root.text,
        children = root.children.map { rewrite(it, texts) },
    )

    private fun remove(root: UiNode, suffixes: Set<String>): UiNode = root.copy(
        children = root.children.filterNot { child -> suffixes.any { child.idIs(it) } }
            .map { remove(it, suffixes) },
    )

    private fun nameHash(name: String): String = TransformRegistry.apply(
        "sha256", TransformRegistry.apply("normalizeCustomerName", name) as String,
    ) as String

    private fun pickupHex(name: String): String = hexIn(
        screens.ruleById("doordash.screen.pickup_arrival")!!.redact.apply(
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = name),
        ).text!!,
        "pickup customer_name",
    )

    /** The sheet's two name ids must remain paired on every rule that can mask user_name. */
    @Test
    fun `every user_name redact carries exactly one ungated gift recipient reference and pickup hex`() {
        val rules = Json.parseToJsonElement(File(TestRulesetFactory.rulesDir, "doordash.json").readText())
            .jsonObject.getValue("screens").jsonArray.map { it.jsonObject }
        // Walk predicates recursively so an all/any/not gate cannot hide a missing or duplicate entry.
        fun containsSuffix(element: JsonElement, suffix: String): Boolean = when (element) {
            is JsonObject -> element["hasIdSuffix"]?.jsonPrimitive?.content == suffix ||
                element.values.any { containsSuffix(it, suffix) }
            is JsonArray -> element.any { containsSuffix(it, suffix) }
            else -> false
        }
        fun entries(rule: JsonObject, suffix: String): List<JsonObject> =
            rule["redact"]?.jsonArray.orEmpty().map { it.jsonObject }.filter { entry ->
                entry["find"]?.let { containsSuffix(it, suffix) } == true
            }.map { JsonObject(it.filterKeys { key -> key != "comment" }) }

        val reference = entries(rules.single { it.getValue("id").jsonPrimitive.content == ruleId },
            "gift_recipient_name").single()
        assertEquals("the reference must be a whole, ungated normalized-name mask",
            Json.parseToJsonElement("""{"find":{"hasIdSuffix":"gift_recipient_name"},"normalize":"customerName"}"""),
            reference)
        val pairedRules = rules.filter { entries(it, "user_name").isNotEmpty() }
        assertTrue("generated DoorDash rules must include user_name redacts", pairedRules.isNotEmpty())
        assertTrue("the live sheet's delivery summary winner must be covered", pairedRules.any {
            it.getValue("id").jsonPrimitive.content == "doordash.screen.delivery_summary_collapsed"
        })
        val expectedHex = pickupHex("Devin R")
        for (rule in pairedRules) {
            val id = rule.getValue("id").jsonPrimitive.content
            val giftEntries = entries(rule, "gift_recipient_name")
            assertEquals("$id must carry exactly one gift recipient entry", 1, giftEntries.size)
            assertEquals("$id must match the gift recipient reference", reference, giftEntries.single())
            val masked = screens.ruleById(id)!!.redact.apply(UiNode(
                viewIdResourceName = "com.doordash.driverapp:id/gift_recipient_name", text = "Devin R",
            )).text.orEmpty()
            assertTrue("$id must mask the whole recipient without any gate: $masked", WHOLE_MASK_HEX.matches(masked))
            assertEquals("$id must use the pickup card's hex", expectedHex, hexIn(masked, id))
        }
    }

    @Test
    fun `both gift sheets recognize and parse the sender as the customer`() {
        for (fixture in fixtures) {
            val tree = load(fixture)
            assertEquals("Gift sender", node(tree, "user_name_label").text)
            val match = screens.matchFirst(tree, platformWire = "doordash")
            assertNotNull("$fixture must recognize", match)
            assertEquals(fixture, ruleId, match!!.ruleId)
            assertEquals(fixture, Flow.TaskDropoffNavigation, match.flow)
            assertNotNull(fixture, match.fields["customerNameHash"])
            assertEquals(fixture, nameHash("[redacted]"), match.fields["customerNameHash"])
        }
    }

    @Test
    fun `gift label is required unless another pre-arrival arm matches`() {
        val deliverTo = rewrite(load(), mapOf("user_name_label" to "Deliver to")).restoreParents()
        assertEquals(ruleId, screens.matchFirst(deliverTo, platformWire = "doordash")?.ruleId)

        val customer = rewrite(load(), mapOf("user_name_label" to "Customer")).restoreParents()
        assertTrue("no navigation lead-in may rescue the gift arm", nodes(customer).none {
            it.text?.contains("Deliver to") == true || it.text?.contains("Heading to") == true
        })
        assertNull("gift label absent must stay UNKNOWN", screens.matchFirst(customer, platformWire = "doordash"))
    }

    @Test
    fun `combined gift and unavailable frame carries the overlay Contact mask`() {
        val gift = load()
        val contact = UiNode(viewIdResourceName = "com.doordash.driverapp:id/step_title", text = "Contact Jane L")
        val combined = gift.copy(children = gift.children + listOf(
            UiNode(
                viewIdResourceName = "com.doordash.driverapp:id/textView_navBar_title",
                text = "Can’t hand order to customer",
            ),
            contact,
        )).restoreParents()
        assertEquals(ruleId, screens.matchFirst(combined, platformWire = "doordash")?.ruleId)

        val masked = preArrival.redact.apply(combined)
        val title = node(masked, "step_title").text!!
        assertTrue("Contact name must mask whole: $title", wholeMaskAfter("Contact ").matches(title))
        val overlayTitle = screens.ruleById("doordash.screen.dropoff_customer_unavailable")!!.redact.apply(
            contact.copy().restoreParents(),
        ).text!!
        assertEquals(hexIn(overlayTitle, "unavailable Contact"), hexIn(title, "combined Contact"))
        assertFalse("overlay customer must not serialize raw", UiNodeSchema.serialize(masked).contains("Jane L"))

        val support = rewrite(combined, mapOf("step_title" to "Contact support")).restoreParents()
        assertEquals(ruleId, screens.matchFirst(support, platformWire = "doordash")?.ruleId)
        assertEquals("Contact support", node(preArrival.redact.apply(support), "step_title").text)
    }

    @Test
    fun `generated pre-arrival Contact entries match customer-unavailable verbatim except comments`() {
        val root = Json.parseToJsonElement(File(TestRulesetFactory.rulesDir, "doordash.json").readText()).jsonObject
        val contactIds = setOf("step_title", "contact_cx_step_title")
        fun contactEntries(id: String): List<JsonObject> {
            val screen = root["screens"]!!.jsonArray.single {
                it.jsonObject["id"]?.jsonPrimitive?.content == id
            }.jsonObject
            val entries = screen["redact"]!!.jsonArray.map { it.jsonObject }.filter { entry ->
                entry["find"]?.jsonObject?.get("all")?.jsonArray?.any {
                    it.jsonObject["hasIdSuffix"]?.jsonPrimitive?.content in contactIds
                } == true
            }
            assertEquals("$id must carry both Contact entries", 2, entries.size)
            return entries.map { JsonObject(it.filterKeys { key -> key != "comment" }) }
        }
        assertEquals(contactEntries("doordash.screen.dropoff_customer_unavailable"), contactEntries(ruleId))
    }

    @Test
    fun `sender and recipient mask whole with pickup name hex while gift chrome stays raw`() {
        val tree = rewrite(load(), mapOf("user_name" to "Marisol Q", "gift_recipient_name" to "Devin R"))
            .restoreParents()
        val match = screens.matchFirst(tree, platformWire = "doordash")!!
        assertEquals(ruleId, match.ruleId)
        assertEquals("the sender owns the pickup lineage", nameHash("Marisol Q"), match.fields["customerNameHash"])

        val masked = preArrival.redact.apply(tree)
        val sender = node(masked, "user_name").text!!
        val recipient = node(masked, "gift_recipient_name").text!!
        assertTrue("sender must mask whole: $sender", WHOLE_MASK_HEX.matches(sender))
        assertTrue("recipient must mask whole: $recipient", WHOLE_MASK_HEX.matches(recipient))
        val senderHex = hexIn(sender, "gift sender")
        val recipientHex = hexIn(recipient, "gift recipient")
        assertEquals(pickupHex("Marisol Q"), senderHex)
        assertEquals(pickupHex("Devin R"), recipientHex)
        assertFalse("distinct names must keep distinct mask hex", senderHex == recipientHex)
        assertEquals("Gift recipient", node(masked, "gift_name_label").text)
        assertEquals("Expected gift", node(masked, "gift_banner_title").text)
        assertEquals("Leave it at the door", node(masked, "instructions_title").text)
        val serialized = UiNodeSchema.serialize(masked)
        assertFalse("sender must not serialize raw", serialized.contains("Marisol Q"))
        assertFalse("recipient must not serialize raw", serialized.contains("Devin R"))
    }

    @Test
    fun `without the gift block Gift sender alone cannot claim pre-arrival`() {
        // ConstraintLayout's gift group and its sibling views form one visual block.
        val giftBlock = setOf(
            "group_gift_banner", "recipient_icon", "gift_name_label", "gift_recipient_name",
            "gift_recipient_instructions_divider", "gift_banner_icon",
            "gift_banner_title", "gift_banner_body",
        )
        val tree = remove(load(), giftBlock).restoreParents()
        assertEquals("Gift sender", node(tree, "user_name_label").text)
        assertTrue(nodes(tree).none { candidate -> giftBlock.any { candidate.idIs(it) } })
        assertNotNull("call_button is not gift-only chrome", node(tree, "call_button"))
        assertNull("gift block removed must stay UNKNOWN", screens.matchFirst(tree, platformWire = "doordash"))
    }

    @Test
    fun `a missing sender never falls back to parsing the recipient`() {
        val tree = remove(
            rewrite(load(), mapOf("gift_recipient_name" to "Devin R")), setOf("user_name"),
        ).restoreParents()
        val match = screens.matchFirst(tree, platformWire = "doordash")!!
        assertEquals(ruleId, match.ruleId)
        assertNull("recipient must never become the ordering customer", match.fields["customerNameHash"])
    }

    @Test
    fun `alcohol render cannot parse user_name without the immediately preceding gift label`() {
        val tree = UiNode(children = listOf(
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/drop_off_bottom_sheet_container"),
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/alcohol_dropoff_ic_scan"),
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/directions_button"),
            UiNode(text = "Gift sender"),
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/user_name_label", text = "Customer"),
            UiNode(viewIdResourceName = "com.doordash.driverapp:id/user_name", text = "Marisol Q"),
        )).restoreParents()
        val match = screens.matchFirst(tree, platformWire = "doordash")!!
        assertEquals(ruleId, match.ruleId)
        assertNull("a gift label elsewhere must not rescue the alcohol name parse", match.fields["customerNameHash"])
    }

    @Test
    fun `UNKNOWN id backstop masks the gift recipient without a lead-in`() {
        val recipient = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/gift_recipient_name",
            text = "Devin R",
        )
        assertEquals("[redacted]", CustomerTextMarkers.scrubUnknown(recipient).text)
    }
}
