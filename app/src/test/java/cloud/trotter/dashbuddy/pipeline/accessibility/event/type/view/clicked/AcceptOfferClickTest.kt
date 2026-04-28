package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [ClickClassifier] → [ClickInfo.AcceptOffer].
 */
class AcceptOfferClickTest {

    private val classifier = ClickClassifier()

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    @Test
    fun `accept_button id classifies as AcceptOffer`() {
        assertEquals(ClickInfo.AcceptOffer, classifier.classify(node(viewId = "accept_button")))
    }

    @Test
    fun `accept_button with extra text is still AcceptOffer`() {
        assertEquals(ClickInfo.AcceptOffer, classifier.classify(node(viewId = "accept_button", text = "Accept")))
    }

    @Test
    fun `different button id is not AcceptOffer`() {
        assertTrue(classifier.classify(node(viewId = "reject_button")) !is ClickInfo.AcceptOffer)
    }

    @Test
    fun `null id is not AcceptOffer`() {
        assertTrue(classifier.classify(node(viewId = null, text = "Accept")) !is ClickInfo.AcceptOffer)
    }
}
