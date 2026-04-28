package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [ClickClassifier] → [ClickInfo.Unknown].
 *
 * Add a row here whenever a new unrecognized click is found in session logs.
 * When the pattern is understood and a new subtype is added, move the test to that type's file.
 */
class UnknownClickTest {

    private val classifier = ClickClassifier()

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    @Test
    fun `unrecognized button id is Unknown`() {
        assertTrue(classifier.classify(node(viewId = "confirm_delivery_button")) is ClickInfo.Unknown)
    }

    @Test
    fun `Unknown preserves nodeId`() {
        val result = classifier.classify(node(viewId = "complete_delivery_btn")) as ClickInfo.Unknown
        assertEquals("complete_delivery_btn", result.nodeId)
    }

    @Test
    fun `Unknown preserves nodeText`() {
        val result = classifier.classify(node(viewId = "primary_action_button", text = "Complete delivery")) as ClickInfo.Unknown
        assertEquals("Complete delivery", result.nodeText)
    }

    @Test
    fun `no_id placeholder is stripped from nodeId`() {
        val result = classifier.classify(node(viewId = "no_id", text = "Got it")) as ClickInfo.Unknown
        assertNull(result.nodeId)
    }

    @Test
    fun `blank viewId is stripped from nodeId`() {
        val result = classifier.classify(node(viewId = "", text = "Submit")) as ClickInfo.Unknown
        assertNull(result.nodeId)
    }

    @Test
    fun `null id and null text yields Unknown with null fields`() {
        val result = classifier.classify(node()) as ClickInfo.Unknown
        assertNull(result.nodeId)
        assertNull(result.nodeText)
    }

    // =========================================================================
    // Observed in field — not yet classified
    // =========================================================================

    @Test
    fun `'Got it' on confirmation dialog is Unknown`() {
        // Seen on Crimson transfer and PIN delivery intro screens
        assertTrue(classifier.classify(node(text = "Got it")) is ClickInfo.Unknown)
    }

    @Test
    fun `'Continue' button is Unknown`() {
        assertTrue(classifier.classify(node(text = "Continue")) is ClickInfo.Unknown)
    }

    @Test
    fun `'Complete delivery' button is Unknown`() {
        // Seen on PIN delivery flow — candidate for ConfirmDelivery subtype
        assertTrue(classifier.classify(node(viewId = "primary_action_button", text = "Complete delivery")) is ClickInfo.Unknown)
    }
}
