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
 * Regression tests for [ClickClassifier] -> [Observation.Click] with intent "accept_offer".
 */
class AcceptOfferClickTest {

    private val classifier = ClickClassifier(mock<JsonRuleInterpreter>())

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    private fun Observation.Click.intent(): String =
        (parsed as ParsedFields.ClickFields).intent

    @Test
    fun `accept_button id classifies as AcceptOffer`() {
        assertEquals("accept_offer", classifier.classify(node(viewId = "accept_button")).intent())
    }

    @Test
    fun `accept_button with extra text is still AcceptOffer`() {
        assertEquals("accept_offer", classifier.classify(node(viewId = "accept_button", text = "Accept")).intent())
    }

    @Test
    fun `different button id is not AcceptOffer`() {
        assertNotEquals("accept_offer", classifier.classify(node(viewId = "reject_button")).intent())
    }

    @Test
    fun `null id is not AcceptOffer`() {
        assertNotEquals("accept_offer", classifier.classify(node(viewId = null, text = "Accept")).intent())
    }
}
