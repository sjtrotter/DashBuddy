package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class ClickClassifierTest {

    private val classifier = ObservationClassifier(
        mock<JsonRuleInterpreter> {
            on { clickRuleset } doReturn TestRulesetFactory.clickRuleset
        },
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
    )

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun node(
        viewId: String? = null,
        text: String? = null,
        contentDescription: String? = null,
    ) = UiNode(
        viewIdResourceName = viewId,
        text = text,
        contentDescription = contentDescription,
    )

    private fun classifyClick(node: UiNode, screenTarget: String? = null): Observation.Click {
        // Prime this platform's screen context so rules with screenIs constraints can
        // match (#438 item 2 — the classifier now keys screen targets per platform wire).
        // The click carries DoorDash's package so it resolves to the "doordash" wire.
        @Suppress("UNCHECKED_CAST")
        val targets = ObservationClassifier::class.java
            .getDeclaredField("lastScreenTargets").apply { isAccessible = true }
            .get(classifier) as MutableMap<String, String>
        targets.clear()
        if (screenTarget != null) targets[Platform.DoorDash.wire] = screenTarget
        return classifier.classify(
            PipelineEvent.Click(
                System.currentTimeMillis(), node,
                packageName = Platform.DoorDash.packageName,
            ),
        )
    }

    private fun Observation.Click.intent(): String =
        (parsed as ParsedFields.ClickFields).intent

    private fun Observation.Click.clickFields(): ParsedFields.ClickFields =
        parsed as ParsedFields.ClickFields

    // =========================================================================
    // AcceptOffer
    // =========================================================================

    @Test
    fun `classifies accept_button as AcceptOffer`() {
        val result = classifyClick(node(viewId = "accept_button"), "offer_popup")
        assertEquals("accept_offer", result.intent())
    }

    @Test
    fun `AcceptOffer takes priority over text when both present`() {
        val result = classifyClick(node(viewId = "accept_button", text = "Decline offer"), "offer_popup")
        assertEquals("accept_offer", result.intent())
    }

    // =========================================================================
    // DeclineOffer
    // =========================================================================

    @Test
    fun `classifies 'Decline offer' text as DeclineOffer`() {
        val result = classifyClick(node(text = "Decline offer"), "offer_popup_confirm_decline")
        assertEquals("decline_offer", result.intent())
    }

    @Test
    fun `DeclineOffer matches case-insensitively`() {
        val result = classifyClick(node(text = "decline offer"), "offer_popup_confirm_decline")
        assertEquals("decline_offer", result.intent())
    }

    // =========================================================================
    // ArrivedAtStore
    // =========================================================================

    @Test
    fun `classifies primary_action_button + 'Arrived at store' text as ArrivedAtStore`() {
        val result = classifyClick(
            node(viewId = "primary_action_button", text = "Arrived at store"), "pickup_arrival"
        )
        assertEquals("arrived_at_store", result.intent())
    }

    @Test
    fun `classifies primary_action_button + 'Arrived' text as ArrivedAtStore`() {
        val result = classifyClick(
            node(viewId = "primary_action_button", text = "Arrived"), "pickup_arrival"
        )
        assertEquals("arrived_at_store", result.intent())
    }

    @Test
    fun `primary_action_button without Arrived text is Unknown`() {
        val result = classifyClick(
            node(viewId = "primary_action_button", text = "Confirm pickup")
        )
        assertEquals("unknown", result.intent())
    }

    @Test
    fun `Arrived text without primary_action_button is Unknown`() {
        val result = classifyClick(
            node(viewId = "some_other_button", text = "Arrived")
        )
        assertEquals("unknown", result.intent())
    }

    // =========================================================================
    // Unknown
    // =========================================================================

    @Test
    fun `returns Unknown for unrecognized node`() {
        val result = classifyClick(node(viewId = "some_button", text = "Some action"))
        assertEquals("unknown", result.intent())
    }

    @Test
    fun `Unknown preserves nodeId for analysis`() {
        val result = classifyClick(node(viewId = "confirm_delivery_button"))
        val fields = result.clickFields()
        assertEquals("confirm_delivery_button", fields.nodeId)
    }

    @Test
    fun `Unknown preserves nodeText for analysis`() {
        val result = classifyClick(node(viewId = "primary_action_button", text = "Complete delivery"))
        val fields = result.clickFields()
        assertEquals("Complete delivery", fields.nodeText)
    }

    @Test
    fun `Unknown with null viewId and null text has null fields`() {
        val result = classifyClick(node())
        val fields = result.clickFields()
        assertNull(fields.nodeId)
        assertNull(fields.nodeText)
    }

    // =========================================================================
    // Per-platform screen context (#438 item 2)
    // =========================================================================

    @Test
    fun `another platform's screen target does not gate this platform's click`() {
        // Prime ONLY Uber's screen context with pickup_arrival, then classify a
        // DoorDash click whose rule requires screenIs=pickup_arrival. Before item 2
        // the single global would have leaked Uber's screen and matched; now the
        // DoorDash click has no primed target of its own and stays Unknown.
        @Suppress("UNCHECKED_CAST")
        val targets = ObservationClassifier::class.java
            .getDeclaredField("lastScreenTargets").apply { isAccessible = true }
            .get(classifier) as MutableMap<String, String>
        targets.clear()
        targets[Platform.Uber.wire] = "pickup_arrival"

        val result = classifier.classify(
            PipelineEvent.Click(
                System.currentTimeMillis(),
                node(viewId = "primary_action_button", text = "Arrived"),
                packageName = Platform.DoorDash.packageName,
            ),
        )
        assertEquals("unknown", result.intent())
    }

    @Test
    fun `Unknown strips no_id placeholder from nodeId`() {
        val result = classifyClick(node(viewId = "no_id", text = "Got it"))
        val fields = result.clickFields()
        assertNull(fields.nodeId)
        assertNotNull(fields.nodeText)
    }
}
