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
 * Regression tests for [ClickClassifier] -> [Observation.Click] with intent "arrived_at_store".
 */
class ArrivedAtStoreClickTest {

    private val classifier = ClickClassifier(mock<JsonRuleInterpreter>())

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    private fun Observation.Click.intent(): String =
        (parsed as ParsedFields.ClickFields).intent

    @Test
    fun `primary_action_button + 'Arrived at store' classifies as ArrivedAtStore`() {
        assertEquals(
            "arrived_at_store",
            classifier.classify(node(viewId = "primary_action_button", text = "Arrived at store")).intent()
        )
    }

    @Test
    fun `primary_action_button + 'Arrived' short form classifies as ArrivedAtStore`() {
        assertEquals(
            "arrived_at_store",
            classifier.classify(node(viewId = "primary_action_button", text = "Arrived")).intent()
        )
    }

    @Test
    fun `primary_action_button without Arrived text is not ArrivedAtStore`() {
        assertNotEquals(
            "arrived_at_store",
            classifier.classify(node(viewId = "primary_action_button", text = "Confirm pickup")).intent()
        )
    }

    @Test
    fun `'Arrived at store' text on wrong button id is not ArrivedAtStore`() {
        assertNotEquals(
            "arrived_at_store",
            classifier.classify(node(viewId = "some_button", text = "Arrived at store")).intent()
        )
    }

    @Test
    fun `null id with Arrived text is not ArrivedAtStore`() {
        assertNotEquals(
            "arrived_at_store",
            classifier.classify(node(viewId = null, text = "Arrived at store")).intent()
        )
    }

    @Test
    fun `primary_action_button with null text is not ArrivedAtStore`() {
        assertNotEquals(
            "arrived_at_store",
            classifier.classify(node(viewId = "primary_action_button", text = null)).intent()
        )
    }
}
