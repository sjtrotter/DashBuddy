package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import org.junit.Assert.assertEquals
import org.mockito.kotlin.doReturn
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Regression tests for [ClickClassifier] -> [Observation.Click] with intent "unknown".
 *
 * Add a row here whenever a new unrecognized click is found in session logs.
 * When the pattern is understood and a new subtype is added, move the test to that type's file.
 */
class UnknownClickTest {

    private val classifier = ClickClassifier(
        mock<JsonRuleInterpreter>(),
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
    )

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    private fun Observation.Click.intent(): String =
        (parsed as ParsedFields.ClickFields).intent

    private fun Observation.Click.clickFields(): ParsedFields.ClickFields =
        parsed as ParsedFields.ClickFields

    @Test
    fun `unrecognized button id is Unknown`() {
        assertEquals("unknown", classifier.classify(node(viewId = "confirm_delivery_button")).intent())
    }

    @Test
    fun `Unknown preserves nodeId`() {
        val fields = classifier.classify(node(viewId = "complete_delivery_btn")).clickFields()
        assertEquals("complete_delivery_btn", fields.nodeId)
    }

    @Test
    fun `Unknown preserves nodeText`() {
        val fields = classifier.classify(node(viewId = "primary_action_button", text = "Complete delivery")).clickFields()
        assertEquals("Complete delivery", fields.nodeText)
    }

    @Test
    fun `no_id placeholder is stripped from nodeId`() {
        val fields = classifier.classify(node(viewId = "no_id", text = "Got it")).clickFields()
        assertNull(fields.nodeId)
    }

    @Test
    fun `blank viewId is stripped from nodeId`() {
        val fields = classifier.classify(node(viewId = "", text = "Submit")).clickFields()
        assertNull(fields.nodeId)
    }

    @Test
    fun `null id and null text yields Unknown with null fields`() {
        val fields = classifier.classify(node()).clickFields()
        assertNull(fields.nodeId)
        assertNull(fields.nodeText)
    }

    // =========================================================================
    // Observed in field — not yet classified
    // =========================================================================

    @Test
    fun `'Got it' on confirmation dialog is Unknown`() {
        // Seen on Crimson transfer and PIN delivery intro screens
        assertEquals("unknown", classifier.classify(node(text = "Got it")).intent())
    }

    @Test
    fun `'Continue' button is Unknown`() {
        assertEquals("unknown", classifier.classify(node(text = "Continue")).intent())
    }

    @Test
    fun `'Complete delivery' button is Unknown`() {
        // Seen on PIN delivery flow — candidate for ConfirmDelivery subtype
        assertEquals("unknown", classifier.classify(node(viewId = "primary_action_button", text = "Complete delivery")).intent())
    }
}
