package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration tests that compile the production rule JSON files and run
 * the resulting rulesets against known inputs.
 *
 * These tests load the files directly from source (no Android Context needed),
 * bypassing [JsonRuleInterpreter] so no dependency injection is required.
 */
class DefaultRulesIntegrationTest {

    private lateinit var screenRuleset: Ruleset<UiNode>
    private lateinit var clickRuleset: Ruleset<UiNode>
    private lateinit var notificationRuleset: Ruleset<RawNotificationData>

    @Before
    fun loadRules() {
        val dir = File(TestRulesetFactory.rulesDir)
        val allScreens = mutableListOf<CompiledRule<UiNode>>()
        val allClicks = mutableListOf<CompiledRule<UiNode>>()
        val allNotifications = mutableListOf<CompiledRule<RawNotificationData>>()

        dir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["screens"]?.jsonArray
                ?.let { allScreens += RuleCompiler.compileRules<UiNode>(it, RuleContext.SCREEN) }
            root["clicks"]?.jsonArray
                ?.let { allClicks += RuleCompiler.compileRules<UiNode>(it, RuleContext.CLICK) }
            root["notifications"]?.jsonArray
                ?.let { allNotifications += RuleCompiler.compileRules<RawNotificationData>(it, RuleContext.NOTIFICATION) }
        }

        screenRuleset = Ruleset(allScreens)
        clickRuleset = Ruleset(allClicks)
        notificationRuleset = Ruleset(allNotifications)
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
        assertEquals("accept_offer", clickRuleset.matchFirst(node, screenTarget = "offer_popup")?.intent)
    }

    @Test
    fun `'Decline offer' text classifies as decline_offer`() {
        val node = UiNode(text = "Decline offer")
        assertEquals("decline_offer", clickRuleset.matchFirst(node, screenTarget = "offer_popup_confirm_decline")?.intent)
    }

    @Test
    fun `primary_action_button + Arrived at store classifies as arrived_at_store`() {
        val node = UiNode(
            viewIdResourceName = "com.doordash.driverapp:id/primary_action_button",
            text = "Arrived at store",
        )
        assertEquals("arrived_at_store", clickRuleset.matchFirst(node, screenTarget = "pickup_arrival")?.intent)
    }

    @Test
    fun `unrecognized click node returns null`() {
        val node = UiNode(viewIdResourceName = "com.doordash.driverapp:id/some_unknown_btn")
        assertNull(clickRuleset.matchFirst(node))
    }

    // =========================================================================
    // Notification classification — spot checks
    // =========================================================================

    @Test
    fun `New Order title classifies as new_order`() {
        val raw = raw(title = "New Order")
        assertEquals("new_order", notificationRuleset.matchFirst(raw)?.intent)
    }

    @Test
    fun `Scheduled dash expired notification classifies correctly`() {
        val raw = raw(text = "Your scheduled dash has expired")
        assertEquals("scheduled_dash_expired", notificationRuleset.matchFirst(raw)?.intent)
    }

    @Test
    fun `AdditionalTip notification extracts fields correctly`() {
        val raw = raw(bigText = "added \$5.00 tip on a past H-E-B order delivered at 4/26, 3:15 PM")
        val result = notificationRuleset.matchFirst(raw)
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
        assertNull(notificationRuleset.matchFirst(raw))
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
    // Parse-output regression on real captures (#433)
    // =========================================================================

    @Test
    fun `timeline task rows parse storeHint and deadline from the real bullet separator`() {
        // The rule's extractBefore/extractAfter separator was double-encoded
        // mojibake (" ï¿½ ") instead of the real bullet (" • ") the captures
        // contain — extractAfter returned null, so storeHint silently parsed
        // null on EVERY timeline screen while the intent-only golden guard
        // stayed green (#433). This pins the parse OUTPUT on real snapshots.
        val snapshots = TestResourceLoader.loadSnapshots("snapshots/timeline")
        assertTrue("timeline corpus must not be empty", snapshots.isNotEmpty())

        val taskEntries = snapshots.mapNotNull { (_, tree, _) ->
            val result = screenRuleset.matchFirst(tree, platformWire = "doordash")
                ?: return@mapNotNull null
            if (result.intent != "timeline") return@mapNotNull null
            val parsed = ParsedFieldsFactory.create(result.shape, result.fields)
                as? cloud.trotter.dashbuddy.domain.state.ParsedFields.TimelineFields
            parsed?.tasks
        }.flatten()

        assertTrue("expected parsed timeline task entries across the corpus", taskEntries.isNotEmpty())
        assertTrue(
            "at least one task row must parse a storeHint (null on every row before #433)",
            taskEntries.any { it.storeHint != null },
        )
        assertTrue(
            "at least one task row must parse a deadline",
            taskEntries.any { it.deadline != null },
        )
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
