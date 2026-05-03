package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration tests that compile the production [rules.default.json] file and run
 * the resulting rulesets against known inputs.
 *
 * These tests load the file directly from source (no Android Context needed),
 * bypassing [JsonRuleInterpreter] so no dependency injection is required.
 */
class DefaultRulesIntegrationTest {

    private lateinit var screenRuleset: ScreenRuleset
    private lateinit var clickRuleset: ClickRuleset
    private lateinit var notificationRuleset: NotificationRuleset

    @Before
    fun loadRules() {
        val json = File("src/main/assets/rules.default.json").readText()
        val root = Json.parseToJsonElement(json).jsonObject

        val screens = root["screens"]?.jsonArray
            ?.let { RuleCompiler.compileScreenRules(it) } ?: emptyList()
        val clicks = root["clicks"]?.jsonArray
            ?.let { RuleCompiler.compileClickRules(it) } ?: emptyList()
        val notifications = root["notifications"]?.jsonArray
            ?.let { RuleCompiler.compileNotificationRules(it) } ?: emptyList()

        screenRuleset = ScreenRuleset(screens)
        clickRuleset = ClickRuleset(clicks)
        notificationRuleset = NotificationRuleset(notifications)
    }

    // =========================================================================
    // Sanity — rule counts are non-zero
    // =========================================================================

    @Test
    fun `screen ruleset has rules`() {
        assertTrue("Expected screen rules, got 0", screenRuleset.ruleCount > 0)
    }

    @Test
    fun `click ruleset has rules`() {
        assertTrue("Expected click rules, got 0", clickRuleset.ruleCount > 0)
    }

    @Test
    fun `notification ruleset has rules`() {
        assertTrue("Expected notification rules, got 0", notificationRuleset.ruleCount > 0)
    }

    // =========================================================================
    // Click classification — spot checks
    // =========================================================================

    @Test
    fun `accept_button id classifies as accept_offer`() {
        val node = UiNode(viewIdResourceName = "com.doordash.driverapp:id/accept_button")
        assertEquals("accept_offer", clickRuleset.classifyFirst(node)?.intent)
    }

    @Test
    fun `'Decline offer' text classifies as decline_offer`() {
        val node = UiNode(text = "Decline offer")
        assertEquals("decline_offer", clickRuleset.classifyFirst(node)?.intent)
    }

    @Test
    fun `primary_action_button + Arrived at store classifies as arrived_at_store`() {
        val node = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/primary_action_button",
            text = "Arrived at store",
        )
        assertEquals("arrived_at_store", clickRuleset.classifyFirst(node)?.intent)
    }

    @Test
    fun `unrecognized click node returns null`() {
        val node = UiNode(viewIdResourceName = "com.doordash.driverapp:id/some_unknown_btn")
        assertNull(clickRuleset.classifyFirst(node))
    }

    // =========================================================================
    // Notification classification — spot checks
    // =========================================================================

    @Test
    fun `New Order title classifies as new_order`() {
        val raw = raw(title = "New Order")
        assertEquals("new_order", notificationRuleset.classifyFirst(raw)?.intent)
    }

    @Test
    fun `Scheduled dash expired notification classifies correctly`() {
        val raw = raw(text = "Your scheduled dash has expired")
        assertEquals("scheduled_dash_expired", notificationRuleset.classifyFirst(raw)?.intent)
    }

    @Test
    fun `AdditionalTip notification extracts fields correctly`() {
        val raw = raw(bigText = "added \$5.00 tip on a past H-E-B order delivered at 4/26, 3:15 PM")
        val result = notificationRuleset.classifyFirst(raw)
        assertNotNull("Expected AdditionalTip result", result)
        assertEquals("additional_tip", result!!.intent)
        assertEquals(5.00, result.fields["amount"] as Double, 0.001)
        assertEquals("H-E-B", result.fields["storeName"])
        assertEquals("4/26, 3:15 PM", result.fields["deliveredAt"])
    }

    @Test
    fun `unrecognized notification returns null`() {
        val raw = raw(title = "DoorDash", text = "Something not yet classified")
        // None of the rules should match — Unknown falls back to the caller
        assertNull(notificationRuleset.classifyFirst(raw))
    }

    // =========================================================================
    // File loading edge cases
    // =========================================================================

    @Test
    fun `file compiles without RuleCompileException`() {
        // @Before would have thrown if compilation failed; reaching here means success
        assertTrue(screenRuleset.ruleCount > 0)
        assertTrue(clickRuleset.ruleCount > 0)
        assertTrue(notificationRuleset.ruleCount > 0)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun raw(title: String? = null, text: String? = null, bigText: String? = null) =
        RawNotificationData(
            title = title, text = text, bigText = bigText, tickerText = null,
            packageName = "com.doordash.driverapp", postTime = 0L, isClearable = true,
        )
}
