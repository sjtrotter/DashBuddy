package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Regression tests for [ClickClassifier] -> [Observation.Click] with intent "decline_offer".
 */
class DeclineOfferClickTest {

    private val classifier = ClickClassifier(mock<JsonRuleInterpreter>())

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    private fun Observation.Click.intent(): String =
        (parsed as ParsedFields.ClickFields).intent

    @Test
    fun `'Decline offer' text classifies as DeclineOffer`() {
        assertEquals("decline_offer", classifier.classify(node(text = "Decline offer")).intent())
    }

    @Test
    fun `matches case-insensitively`() {
        assertEquals("decline_offer", classifier.classify(node(text = "decline offer")).intent())
        assertEquals("decline_offer", classifier.classify(node(text = "DECLINE OFFER")).intent())
    }

    @Test
    fun `partial text 'Decline' without 'offer' is not DeclineOffer`() {
        // hasText checks for substring match — "Decline" alone still contains "Decline offer"? No.
        assertNotEquals("decline_offer", classifier.classify(node(text = "Decline")).intent())
    }

    @Test
    fun `null text is not DeclineOffer`() {
        assertNotEquals("decline_offer", classifier.classify(node(text = null)).intent())
    }

    @Test
    fun `accept_button takes priority over Decline offer text`() {
        // accept_button is checked first
        assertEquals("accept_offer", classifier.classify(node(viewId = "accept_button", text = "Decline offer")).intent())
    }
}
