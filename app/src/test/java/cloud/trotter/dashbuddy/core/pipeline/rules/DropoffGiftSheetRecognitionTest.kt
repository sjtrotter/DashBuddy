package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.WHOLE_MASK_HEX
import cloud.trotter.dashbuddy.core.pipeline.rules.CaptureRedactionCorpusTest.Companion.hexIn
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
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
    private fun UiNode.idIs(suffix: String): Boolean =
        viewIdResourceName?.endsWith(":id/$suffix") == true

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
            "call_button", "gift_recipient_instructions_divider", "gift_banner_icon",
            "gift_banner_title", "gift_banner_body",
        )
        val tree = remove(load(), giftBlock).restoreParents()
        assertEquals("Gift sender", node(tree, "user_name_label").text)
        assertTrue(nodes(tree).none { candidate -> giftBlock.any { candidate.idIs(it) } })
        val winner = screens.matchFirst(tree, platformWire = "doordash")?.ruleId ?: "UNKNOWN"
        println("#1314 gift block removed: $winner")
        assertFalse("gift block removed; winner=$winner", winner == ruleId)
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
    fun `UNKNOWN id backstop masks the gift recipient without a lead-in`() {
        val recipient = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/gift_recipient_name",
            text = "Devin R",
        )
        assertEquals("[redacted]", CustomerTextMarkers.scrubUnknown(recipient).text)
    }
}
