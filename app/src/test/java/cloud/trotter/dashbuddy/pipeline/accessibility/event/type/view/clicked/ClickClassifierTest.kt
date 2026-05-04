package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class ClickClassifierTest {

    private val classifier = ClickClassifier(
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

    private fun Observation.Click.intent(): String =
        (parsed as ParsedFields.ClickFields).intent

    private fun Observation.Click.clickFields(): ParsedFields.ClickFields =
        parsed as ParsedFields.ClickFields

    // =========================================================================
    // AcceptOffer
    // =========================================================================

    @Test
    fun `classifies accept_button as AcceptOffer`() {
        val result = classifier.classify(node(viewId = "accept_button"))
        assertEquals("accept_offer", result.intent())
    }

    @Test
    fun `AcceptOffer takes priority over text when both present`() {
        val result = classifier.classify(node(viewId = "accept_button", text = "Decline offer"))
        assertEquals("accept_offer", result.intent())
    }

    // =========================================================================
    // DeclineOffer
    // =========================================================================

    @Test
    fun `classifies 'Decline offer' text as DeclineOffer`() {
        val result = classifier.classify(node(text = "Decline offer"))
        assertEquals("decline_offer", result.intent())
    }

    @Test
    fun `DeclineOffer matches case-insensitively`() {
        // hasText uses ignoreCase = true internally
        val result = classifier.classify(node(text = "decline offer"))
        assertEquals("decline_offer", result.intent())
    }

    // =========================================================================
    // ArrivedAtStore
    // =========================================================================

    @Test
    fun `classifies primary_action_button + 'Arrived at store' text as ArrivedAtStore`() {
        val result = classifier.classify(
            node(viewId = "primary_action_button", text = "Arrived at store")
        )
        assertEquals("arrived_at_store", result.intent())
    }

    @Test
    fun `classifies primary_action_button + 'Arrived' text as ArrivedAtStore`() {
        val result = classifier.classify(
            node(viewId = "primary_action_button", text = "Arrived")
        )
        assertEquals("arrived_at_store", result.intent())
    }

    @Test
    fun `primary_action_button without Arrived text is Unknown`() {
        val result = classifier.classify(
            node(viewId = "primary_action_button", text = "Confirm pickup")
        )
        assertEquals("unknown", result.intent())
    }

    @Test
    fun `Arrived text without primary_action_button is Unknown`() {
        val result = classifier.classify(
            node(viewId = "some_other_button", text = "Arrived")
        )
        assertEquals("unknown", result.intent())
    }

    // =========================================================================
    // Unknown
    // =========================================================================

    @Test
    fun `returns Unknown for unrecognized node`() {
        val result = classifier.classify(node(viewId = "some_button", text = "Some action"))
        assertEquals("unknown", result.intent())
    }

    @Test
    fun `Unknown preserves nodeId for analysis`() {
        val result = classifier.classify(node(viewId = "confirm_delivery_button"))
        val fields = result.clickFields()
        assertEquals("confirm_delivery_button", fields.nodeId)
    }

    @Test
    fun `Unknown preserves nodeText for analysis`() {
        val result = classifier.classify(node(viewId = "primary_action_button", text = "Complete delivery"))
        val fields = result.clickFields()
        assertEquals("Complete delivery", fields.nodeText)
    }

    @Test
    fun `Unknown with null viewId and null text has null fields`() {
        val result = classifier.classify(node())
        val fields = result.clickFields()
        assertNull(fields.nodeId)
        assertNull(fields.nodeText)
    }

    @Test
    fun `Unknown strips no_id placeholder from nodeId`() {
        val result = classifier.classify(node(viewId = "no_id", text = "Got it"))
        val fields = result.clickFields()
        assertNull(fields.nodeId)
        assertNotNull(fields.nodeText)
    }
}
