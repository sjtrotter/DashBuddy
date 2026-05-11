package cloud.trotter.dashbuddy.core.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.core.pipeline.ObservationClassifier
import cloud.trotter.dashbuddy.core.pipeline.PipelineEvent
import cloud.trotter.dashbuddy.core.pipeline.rules.JsonRuleInterpreter
import org.junit.Assert.assertEquals
import org.mockito.kotlin.doReturn
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Regression tests for [ObservationClassifier] -> [Observation.Click] with intent "unknown".
 *
 * Add a row here whenever a new unrecognized click is found in session logs.
 * When the pattern is understood and a new subtype is added, move the test to that type's file.
 */
class UnknownClickTest {

    private val classifier = ObservationClassifier(
        mock<JsonRuleInterpreter>(),
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
    )

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    private fun classifyClick(node: UiNode): Observation.Click =
        classifier.classify(PipelineEvent.Click(System.currentTimeMillis(), node)) as Observation.Click

    private fun Observation.Click.intent(): String =
        (parsed as ParsedFields.ClickFields).intent

    private fun Observation.Click.clickFields(): ParsedFields.ClickFields =
        parsed as ParsedFields.ClickFields

    @Test
    fun `unrecognized button id is Unknown`() {
        assertEquals("unknown", classifyClick(node(viewId = "confirm_delivery_button")).intent())
    }

    @Test
    fun `Unknown preserves nodeId`() {
        val fields = classifyClick(node(viewId = "complete_delivery_btn")).clickFields()
        assertEquals("complete_delivery_btn", fields.nodeId)
    }

    @Test
    fun `Unknown preserves nodeText`() {
        val fields = classifyClick(node(viewId = "primary_action_button", text = "Complete delivery")).clickFields()
        assertEquals("Complete delivery", fields.nodeText)
    }

    @Test
    fun `no_id placeholder is stripped from nodeId`() {
        val fields = classifyClick(node(viewId = "no_id", text = "Got it")).clickFields()
        assertNull(fields.nodeId)
    }

    @Test
    fun `blank viewId is stripped from nodeId`() {
        val fields = classifyClick(node(viewId = "", text = "Submit")).clickFields()
        assertNull(fields.nodeId)
    }

    @Test
    fun `null id and null text yields Unknown with null fields`() {
        val fields = classifyClick(node()).clickFields()
        assertNull(fields.nodeId)
        assertNull(fields.nodeText)
    }

    // =========================================================================
    // Observed in field — not yet classified
    // =========================================================================

    @Test
    fun `'Got it' on confirmation dialog is Unknown`() {
        assertEquals("unknown", classifyClick(node(text = "Got it")).intent())
    }

    @Test
    fun `'Continue' button is Unknown`() {
        assertEquals("unknown", classifyClick(node(text = "Continue")).intent())
    }

    @Test
    fun `'Complete delivery' button is Unknown`() {
        assertEquals("unknown", classifyClick(node(viewId = "primary_action_button", text = "Complete delivery")).intent())
    }
}
