package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [ClickClassifier] → [ClickInfo.DeclineOffer].
 */
class DeclineOfferClickTest {

    private val classifier = ClickClassifier()

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    @Test
    fun `'Decline offer' text classifies as DeclineOffer`() {
        assertEquals(ClickInfo.DeclineOffer, classifier.classify(node(text = "Decline offer")))
    }

    @Test
    fun `matches case-insensitively`() {
        assertEquals(ClickInfo.DeclineOffer, classifier.classify(node(text = "decline offer")))
        assertEquals(ClickInfo.DeclineOffer, classifier.classify(node(text = "DECLINE OFFER")))
    }

    @Test
    fun `partial text 'Decline' without 'offer' is not DeclineOffer`() {
        // hasText checks for substring match — "Decline" alone still contains "Decline offer"? No.
        assertTrue(classifier.classify(node(text = "Decline")) !is ClickInfo.DeclineOffer)
    }

    @Test
    fun `null text is not DeclineOffer`() {
        assertTrue(classifier.classify(node(text = null)) !is ClickInfo.DeclineOffer)
    }

    @Test
    fun `accept_button takes priority over Decline offer text`() {
        // accept_button is checked first
        assertEquals(ClickInfo.AcceptOffer, classifier.classify(node(viewId = "accept_button", text = "Decline offer")))
    }
}
