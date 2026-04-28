package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickClassifierTest {

    private val classifier = ClickClassifier()

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

    // =========================================================================
    // AcceptOffer
    // =========================================================================

    @Test
    fun `classifies accept_button as AcceptOffer`() {
        val result = classifier.classify(node(viewId = "accept_button"))
        assertEquals(ClickInfo.AcceptOffer, result)
    }

    @Test
    fun `AcceptOffer takes priority over text when both present`() {
        val result = classifier.classify(node(viewId = "accept_button", text = "Decline offer"))
        assertEquals(ClickInfo.AcceptOffer, result)
    }

    // =========================================================================
    // DeclineOffer
    // =========================================================================

    @Test
    fun `classifies 'Decline offer' text as DeclineOffer`() {
        val result = classifier.classify(node(text = "Decline offer"))
        assertEquals(ClickInfo.DeclineOffer, result)
    }

    @Test
    fun `DeclineOffer matches case-insensitively`() {
        // hasText uses ignoreCase = true internally
        val result = classifier.classify(node(text = "decline offer"))
        assertEquals(ClickInfo.DeclineOffer, result)
    }

    // =========================================================================
    // ArrivedAtStore
    // =========================================================================

    @Test
    fun `classifies primary_action_button + 'Arrived at store' text as ArrivedAtStore`() {
        val result = classifier.classify(
            node(viewId = "primary_action_button", text = "Arrived at store")
        )
        assertEquals(ClickInfo.ArrivedAtStore, result)
    }

    @Test
    fun `classifies primary_action_button + 'Arrived' text as ArrivedAtStore`() {
        val result = classifier.classify(
            node(viewId = "primary_action_button", text = "Arrived")
        )
        assertEquals(ClickInfo.ArrivedAtStore, result)
    }

    @Test
    fun `primary_action_button without Arrived text is Unknown`() {
        val result = classifier.classify(
            node(viewId = "primary_action_button", text = "Confirm pickup")
        )
        assertTrue(result is ClickInfo.Unknown)
    }

    @Test
    fun `Arrived text without primary_action_button is Unknown`() {
        val result = classifier.classify(
            node(viewId = "some_other_button", text = "Arrived")
        )
        assertTrue(result is ClickInfo.Unknown)
    }

    // =========================================================================
    // Unknown
    // =========================================================================

    @Test
    fun `returns Unknown for unrecognized node`() {
        val result = classifier.classify(node(viewId = "some_button", text = "Some action"))
        assertTrue(result is ClickInfo.Unknown)
    }

    @Test
    fun `Unknown preserves nodeId for analysis`() {
        val result = classifier.classify(node(viewId = "confirm_delivery_button"))
        val unknown = result as ClickInfo.Unknown
        assertEquals("confirm_delivery_button", unknown.nodeId)
    }

    @Test
    fun `Unknown preserves nodeText for analysis`() {
        val result = classifier.classify(node(viewId = "primary_action_button", text = "Complete delivery"))
        val unknown = result as ClickInfo.Unknown
        assertEquals("Complete delivery", unknown.nodeText)
    }

    @Test
    fun `Unknown with null viewId and null text has null fields`() {
        val result = classifier.classify(node())
        val unknown = result as ClickInfo.Unknown
        assertNull(unknown.nodeId)
        assertNull(unknown.nodeText)
    }

    @Test
    fun `Unknown strips no_id placeholder from nodeId`() {
        val result = classifier.classify(node(viewId = "no_id", text = "Got it"))
        val unknown = result as ClickInfo.Unknown
        assertNull(unknown.nodeId)
        assertNotNull(unknown.nodeText)
    }
}
