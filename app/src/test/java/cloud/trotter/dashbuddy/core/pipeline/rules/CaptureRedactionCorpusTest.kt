package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
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
 * #598 corpus-level guard — the teeth. Runs the PRODUCTION redact predicates
 * over a real (already-redacted) dropoff fixture with a synthetic customer name
 * injected, and pins the fail-closed invariant that every screen rule which
 * hashes PII (`sha256`) also declares a `redact` block. Redaction is
 * capture-only, so this asserts on the SERIALIZED envelope payload, never on
 * recognition (the goldens stay byte-identical).
 */
class CaptureRedactionCorpusTest {

    private val rulesDir = File(TestRulesetFactory.rulesDir)

    private fun serialize(tree: UiNode): String = UiNodeSchema.serialize(tree)

    @Test
    fun `dropoff redact masks an injected customer name but keeps the Deliver to marker`() {
        // A real, already-redacted en-route dropoff card from the committed corpus.
        val fixture = File("src/test/resources/snapshots/dropoff_pre_arrival")
            .listFiles { _, n -> n.endsWith(".json") }!!
            .sorted().first()
        val real = TestResourceLoader.loadNode(fixture)

        // Inject a synthetic PII node the way a live (un-redacted) capture would carry it.
        val injected = UiNode(
            text = "Deliver to Testname Q",
            children = real.children,
        ).restoreParents()

        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_pre_arrival")!!
        val redactedJson = serialize(rule.redact.apply(injected))

        assertFalse("injected customer name must not persist", redactedJson.contains("Testname Q"))
        // #623: the masked portion carries a `[redacted:<4hex>]` distinctness suffix.
        assertTrue(
            "marker kept, name masked",
            Regex("""Deliver to \[redacted:[0-9a-f]{4}\]""").containsMatchIn(redactedJson),
        )
    }

    /**
     * #623 frame-invariance ACROSS RULES (VET V5): the SAME customer token, seen
     * under three different production redact blocks (each with a different marker
     * prefix / node predicate), must redact to the SAME 4hex distinctness suffix —
     * and a DIFFERENT customer to a DIFFERENT suffix. This is what makes
     * multi-customer replay possible without persisting raw PII: the suffix is a
     * stable 16-bit fingerprint of the customer token, aligned with the parse's
     * strip+trim+sha256, not a per-frame value.
     */
    @Test
    fun `same customer redacts to the same 4hex across dropoff_navigation, timeline, and pickup_verify_items`() {
        val suffix = Regex("""\[redacted:([0-9a-f]{4})\]""")
        fun hexOf(masked: String): String =
            suffix.find(masked)!!.groupValues[1]

        // dropoff_navigation: id-keyed title node, keepPrefix "Deliver to ".
        val navRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_navigation")!!
        val navHex = hexOf(
            navRule.redact.apply(
                UiNode(
                    viewIdResourceName = "com.dd:id/bottom_sheet_task_title",
                    text = "Deliver to Jane Q Doe",
                ),
            ).text!!,
        )

        // timeline: text-keyed node, keepPrefix "Deliver to " (also "Pickup for ").
        val timelineRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.timeline")!!
        val timelineHex = hexOf(
            timelineRule.redact.apply(UiNode(text = "Deliver to Jane Q Doe")).text!!,
        )

        // pickup_verify_items: different marker ("Verify items for ") + different id.
        val verifyRule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.pickup_verify_items")!!
        val verifyHex = hexOf(
            verifyRule.redact.apply(
                UiNode(
                    viewIdResourceName = "com.dd:id/order_verification_checklist_title",
                    text = "Verify items for Jane Q Doe",
                ),
            ).text!!,
        )

        assertEquals("same customer must redact to the same 4hex across rules", navHex, timelineHex)
        assertEquals("same customer must redact to the same 4hex across rules", navHex, verifyHex)

        // The suffix equals the first 4 hex of sha256(token) — the same hash the parse persists.
        val expected = java.security.MessageDigest.getInstance("SHA-256")
            .digest("Jane Q Doe".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(4)
        assertEquals("suffix must be first 4 hex of sha256(token)", expected, navHex)

        // A DIFFERENT customer must produce a DIFFERENT suffix (distinctness).
        val otherHex = hexOf(
            timelineRule.redact.apply(UiNode(text = "Deliver to John Smith")).text!!,
        )
        assertFalse("different customers must redact to different 4hex", otherHex == navHex)
    }

    @Test
    fun `dropoff_reminder redact masks the address after the marker (#624)`() {
        // The live leak #624 fixes: "Deliver to door of <address>" had no hash and
        // no redact, so the #598 sha256 gate never fired and the raw address shipped.
        val rule = TestRulesetFactory.screenRuleset.ruleById("doordash.screen.dropoff_reminder")!!
        assertTrue("dropoff_reminder must now carry a redact block", !rule.redact.isEmpty())

        val node = UiNode(text = "Deliver to door of 4B, 123 Real Street").restoreParents()
        val masked = serialize(rule.redact.apply(node))

        assertFalse("street address must not persist", masked.contains("123 Real Street"))
        assertFalse("apt must not persist", masked.contains("4B"))
        assertTrue(
            "marker kept, address masked",
            Regex("""Deliver to door of \[redacted:[0-9a-f]{4}\]""").containsMatchIn(masked),
        )
    }

    @Test
    fun `every screen rule that hashes PII declares a redact block`() {
        var checked = 0
        rulesDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["screens"]?.jsonArray?.forEach { screen ->
                val obj = screen.jsonObject
                if (jsonUsesSha256(obj)) {
                    checked++
                    val redact = obj["redact"]?.jsonArray
                    assertTrue(
                        "screen rule '${obj["id"]?.jsonPrimitive?.content}' hashes PII (sha256) " +
                            "but declares no non-empty redact block (#598)",
                        redact != null && redact.isNotEmpty(),
                    )
                }
            }
        }
        // Guard the guard: the 4 known sha256 dropoff/timeline rules must be seen.
        assertTrue("expected at least the 4 known sha256 screen rules", checked >= 4)
    }

    @Test
    fun `the four known sha256 rules expose a redact block via ruleById`() {
        val ids = listOf(
            "doordash.screen.timeline",
            "doordash.screen.dropoff_navigation",
            "doordash.screen.dropoff_pre_arrival",
            "doordash.screen.dropoff_pre_arrival_completion",
        )
        for (id in ids) {
            val rule = TestRulesetFactory.screenRuleset.ruleById(id)!!
            assertFalse("$id must carry a redact block", rule.redact.isEmpty())
        }
    }

    // =========================================================================
    // #620 — production notification redact blocks
    // =========================================================================

    private fun notif(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        tickerText: String? = null,
        channelId: String? = null,
    ) = RawNotificationData(
        title = title, text = text, bigText = bigText, tickerText = tickerText,
        packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
        channelId = channelId,
    )

    @Test
    fun `production customer_message redact masks the sender and body (#620)`() {
        val rule = TestRulesetFactory.notificationRuleset.ruleById("doordash.notification.customer_message")!!
        assertTrue("customer_message must carry a notif redact block", !rule.notifRedact.isEmpty())
        val masked = rule.notifRedact.apply(
            notif(
                title = "Message from Jennifer",
                text = "The gate code is 4412",
                tickerText = "The gate code is 4412",
                channelId = "dasher-notification-channel-inapp-chat",
            ),
        )
        assertFalse("sender name gone", masked.title!!.contains("Jennifer"))
        assertTrue(Regex("""Message from \[redacted:[0-9a-f]{4}\]""").containsMatchIn(masked.title!!))
        assertFalse("body gone (text)", masked.text!!.contains("4412"))
        assertFalse("body gone (tickerText)", masked.tickerText!!.contains("4412"))
    }

    @Test
    fun `production order_ready redact masks the customer name but keeps the store (#620)`() {
        val rule = TestRulesetFactory.notificationRuleset.ruleById("doordash.notification.order_ready")!!
        assertTrue("order_ready must carry a notif redact block", !rule.notifRedact.isEmpty())
        val masked = rule.notifRedact.apply(
            notif(
                title = "Delivery Update",
                text = "Adam's order is ready for pickup at 7-Eleven.",
                channelId = "dasher-notification-channel-delivery-update",
            ),
        )
        assertFalse("customer name gone", masked.text!!.contains("Adam"))
        assertTrue("store kept — merchants are not PII", masked.text!!.contains("7-Eleven"))
        assertEquals("title (non-PII) untouched", "Delivery Update", masked.title)
    }

    @Test
    fun `a benign notification rule declares no notif redact (#620)`() {
        val rule = TestRulesetFactory.notificationRuleset.ruleById("doordash.notification.new_order")!!
        assertTrue("new_order carries no customer PII → no redact", rule.notifRedact.isEmpty())
    }

    private fun jsonUsesSha256(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
        is kotlinx.serialization.json.JsonPrimitive -> element.isString && element.content == "sha256"
        is kotlinx.serialization.json.JsonObject -> element.values.any { jsonUsesSha256(it) }
        is kotlinx.serialization.json.JsonArray -> element.any { jsonUsesSha256(it) }
    }
}
