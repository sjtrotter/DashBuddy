package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Integration tests for the recognition rules added from the 2026-05 UNKNOWN
 * capture triage (see docs/capture-analysis/2026-05-unknown-triage.md).
 *
 * Like [DefaultRulesIntegrationTest], these compile the production rule JSON
 * (doordash.json / uber.json) and run the real rulesets against minimal,
 * synthetic fixtures — no captured PII. Each positive assertion also doubles
 * as a collision check: where several rules share a notification channel or a
 * screen anchor, asserting the *specific* intent proves priority ordering and
 * reject clauses route the input correctly.
 */
class TriageRulesTest {

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

    private fun notif(tree: RawNotificationData) = notificationRuleset.matchFirst(tree)?.intent
    private fun screen(tree: UiNode) = screenRuleset.matchFirst(tree)?.intent
    private fun click(node: UiNode, screenTarget: String? = null) =
        clickRuleset.matchFirst(node, screenTarget = screenTarget)?.intent

    // =========================================================================
    // Notifications — channel-keyed recognition
    // =========================================================================

    @Test
    fun `new_order — by new-order-v2 channel (title is 'New Delivery!')`() {
        assertEquals(
            "new_order",
            notif(raw(channelId = "dasher-notification-channel-new-order-v2", title = "New Delivery!", text = "New order: go to Chipotle")),
        )
    }

    @Test
    fun `new_order — legacy title path still matches (regression)`() {
        assertEquals("new_order", notif(raw(title = "New Order")))
    }

    @Test
    fun `customer_message — by in-app-chat channel (per-customer title)`() {
        assertEquals(
            "customer_message",
            notif(raw(channelId = "dasher-notification-channel-inapp-chat", title = "Message from Jennifer", text = "thank you")),
        )
    }

    @Test
    fun `order_ready — delivery-update channel + 'ready for pickup'`() {
        assertEquals(
            "order_ready_for_pickup",
            notif(raw(channelId = "dasher-notification-channel-delivery-update", title = "Delivery Update", text = "the order is ready for pickup at the store")),
        )
    }

    @Test
    fun `missed_delivery — delivery-update channel + title`() {
        assertEquals(
            "missed_delivery",
            notif(raw(channelId = "dasher-notification-channel-delivery-update", title = "Missed Delivery", text = "you missed a delivery opportunity")),
        )
    }

    @Test
    fun `earnings_deposit vs crimson_balance — same 'messages' channel, split by text`() {
        assertEquals(
            "earnings_deposit",
            notif(raw(channelId = "dasher-notification-messages", title = "Dasher", text = "your dasher earnings for \$40 have been deposited to your DoorDash Crimson account.")),
        )
        assertEquals(
            "crimson_balance",
            notif(raw(channelId = "dasher-notification-messages", title = "You're building momentum", text = "your DoorDash Crimson savings jar balance is now \$100")),
        )
    }

    @Test
    fun `appboy channel — specific rules beat the marketing catch-all`() {
        assertEquals(
            "transfer_complete",
            notif(raw(channelId = "com_appboy_default_notification_channel", title = "Transfer complete.", text = "funds will arrive shortly")),
        )
        assertEquals(
            "severe_weather",
            notif(raw(channelId = "com_appboy_default_notification_channel", title = "A severe weather warning has been detected in your area")),
        )
        // anything else on the Braze channel falls through to the catch-all
        assertEquals(
            "marketing_promo",
            notif(raw(channelId = "com_appboy_default_notification_channel", title = "Earn an extra \$85 this week!")),
        )
    }

    @Test
    fun `dash-update channel — arrived vs verification vs demand, split by title`() {
        assertEquals(
            "arrived_in_zone",
            notif(raw(channelId = "dasher-notification-channel-dash-update", title = "You have arrived", text = "tap here or return to the dasher app to look for more orders")),
        )
        assertEquals(
            "order_verification_warning",
            notif(raw(channelId = "dasher-notification-channel-dash-update", title = "Confirm you have the right order", text = "the receipt you uploaded may not match")),
        )
        assertEquals(
            "demand_nudge",
            notif(raw(channelId = "dasher-notification-channel-dash-update", title = "Update!", text = "TX: northwest san antonio is busy for shop and deliver right now")),
        )
    }

    @Test
    fun `demand_nudge — legacy 'Dash now' title still matches (regression)`() {
        assertEquals("demand_nudge", notif(raw(title = "Dash now in your area")))
    }

    @Test
    fun `dash_status_ongoing — foreground status channel`() {
        assertEquals(
            "dash_status_ongoing",
            notif(raw(channelId = "dasher-notification-channel-status", title = "DoorDash Driver Dash", text = "You're still dashing. Location updates will be sent")),
        )
    }

    @Test
    fun `insight_achievement — new-insight channel`() {
        assertEquals(
            "insight_achievement",
            notif(raw(channelId = "dasher-notification-channel-new-insight", title = "Order accuracy streak")),
        )
    }

    @Test
    fun `unrecognized notification still returns null`() {
        assertNull(notif(raw(title = "Totally unrelated", text = "nothing matches this")))
    }

    // =========================================================================
    // Screens — new rules + broadenings (positive + collision/regression)
    // =========================================================================

    @Test
    fun `timeline — dash-control header path still matches`() {
        assertEquals("timeline", screen(tree(node(text = "Dash ends at 18:30"), node(text = "Pause orders"))))
    }

    @Test
    fun `timeline — task-list scroll state now matches (broadening)`() {
        assertEquals(
            "timeline",
            screen(tree(node(text = "Current dash"), node(text = "Current task"), node(text = "Deliver to Gregory S"))),
        )
    }

    @Test
    fun `timeline — no longer asserts flow idle (the transient-idle bug)`() {
        assertNull(screenRuleset.matchFirst(tree(node(text = "Dash ends at 18:30"), node(text = "Pause orders")))?.flow)
    }

    @Test
    fun `pickup_pre_arrival — prism-button path still matches`() {
        assertEquals(
            "pickup_pre_arrival",
            screen(tree(node(id = "user_name_label", text = "Pickup from"), node(id = "textView_prism_button_title", text = "Arrived at store"))),
        )
    }

    @Test
    fun `pickup_pre_arrival — expanded bottom-sheet detail now matches (broadening)`() {
        assertEquals(
            "pickup_pre_arrival",
            screen(tree(node(id = "user_name_label", text = "Pickup from"), node(id = "bottom_sheet_container"))),
        )
    }

    @Test
    fun `end_dash_confirm dialog`() {
        assertEquals("end_dash_confirm", screen(tree(node(id = "prism_sheet"), node(text = "End your current dash?"))))
    }

    @Test
    fun `dropoff_photo — recognized and sets a (non-null) dropoff-arrived flow`() {
        val t = tree(node(id = "drop_off_workflow_host_fragment"), node(text = "Take photo of drop-off location"))
        assertEquals("dropoff_photo", screen(t))
        assertNotNull("dropoff completion implies arrival → must carry a flow", screenRuleset.matchFirst(t)?.flow)
    }

    @Test
    fun `dropoff_pin_entry`() {
        assertEquals(
            "dropoff_pin_entry",
            screen(tree(node(id = "drop_off_workflow_host_fragment"), node(text = "Ask the customer for the unique 4-digit PIN"))),
        )
    }

    @Test
    fun `dropoff_handoff`() {
        assertEquals(
            "dropoff_handoff",
            screen(tree(node(id = "drop_off_workflow_host_fragment"), node(text = "Hand it to customer"))),
        )
    }

    @Test
    fun `shopping_item — deeper shop-and-deliver screen`() {
        assertEquals(
            "shopping_item",
            screen(tree(node(id = "fragmentContainerView_shopDeliver"), node(text = "Item details"), node(text = "Scan item barcode"))),
        )
    }

    @Test
    fun `shopping_item — does NOT steal the 'Shop and Deliver' landing (reject keeps pickup_shopping)`() {
        assertEquals(
            "pickup_shopping",
            screen(tree(node(id = "fragmentContainerView_shopDeliver"), node(text = "Shop and Deliver"), node(id = "tab_layout"))),
        )
    }

    @Test
    fun `shopping_checkout`() {
        assertEquals(
            "shopping_checkout",
            screen(tree(node(id = "fragmentContainerView_genericCheckout"), node(text = "Go to any cashier lane"))),
        )
    }

    @Test
    fun `dropoff_geofence_warning`() {
        assertEquals(
            "dropoff_geofence_warning",
            screen(tree(node(text = "Are you at the right location? You seem to be far away from the customer."))),
        )
    }

    @Test
    fun `side_nav_drawer — open menu`() {
        assertEquals(
            "side_nav_drawer",
            screen(tree(node(id = "side_nav_content_container"), node(text = "Ratings"), node(text = "Promos"), node(text = "Preferences"))),
        )
    }

    @Test
    fun `side_nav_drawer does NOT shadow idle_map (drawer closed — no menu text)`() {
        assertEquals(
            "idle_map",
            screen(tree(node(desc = "Earnings Mode Switcher"), node(id = "side_nav_compose_view"))),
        )
    }

    @Test
    fun `camera_capture viewfinder`() {
        assertEquals("camera_capture", screen(tree(node(id = "camera_preview"))))
    }

    @Test
    fun `pickup_issue_menu`() {
        assertEquals("pickup_issue_menu", screen(tree(node(text = "What pickup issues can we help with?"))))
    }

    @Test
    fun `chat_conversation — recognized and asserts NO flow (reachable from any state)`() {
        val t = tree(node(id = "ddchat_holder_base"), node(text = "Type message"))
        assertEquals("chat_conversation", screen(t))
        assertNull("chat is flow-agnostic → must not assert a flow", screenRuleset.matchFirst(t)?.flow)
    }

    // =========================================================================
    // Helpers — minimal synthetic fixtures (no captured PII)
    // =========================================================================

    private fun raw(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        channelId: String? = null,
        isOngoing: Boolean = false,
    ) = RawNotificationData(
        title = title,
        text = text,
        tickerText = null,
        bigText = bigText,
        packageName = "com.doordash.driverapp",
        postTime = 0L,
        isClearable = true,
        isOngoing = isOngoing,
        channelId = channelId,
    )

    /** A node carrying an id and/or text, with optional children. */
    private fun node(
        id: String? = null,
        text: String? = null,
        desc: String? = null,
        children: List<UiNode> = emptyList(),
    ) = UiNode(
        viewIdResourceName = id?.let { "com.doordash.driverapp:id/$it" },
        text = text,
        contentDescription = desc,
        children = children.toMutableList(),
    )

    /** A screen tree: a content root wrapping the given nodes. */
    private fun tree(vararg kids: UiNode): UiNode =
        node(id = "content", children = kids.toList()).also { it.restoreParents() }
}
