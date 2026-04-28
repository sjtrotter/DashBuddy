package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.view.clicked

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [ClickClassifier] → [ClickInfo.ArrivedAtStore].
 */
class ArrivedAtStoreClickTest {

    private val classifier = ClickClassifier()

    private fun node(viewId: String? = null, text: String? = null) =
        UiNode(viewIdResourceName = viewId, text = text)

    @Test
    fun `primary_action_button + 'Arrived at store' classifies as ArrivedAtStore`() {
        assertEquals(
            ClickInfo.ArrivedAtStore,
            classifier.classify(node(viewId = "primary_action_button", text = "Arrived at store"))
        )
    }

    @Test
    fun `primary_action_button + 'Arrived' short form classifies as ArrivedAtStore`() {
        assertEquals(
            ClickInfo.ArrivedAtStore,
            classifier.classify(node(viewId = "primary_action_button", text = "Arrived"))
        )
    }

    @Test
    fun `primary_action_button without Arrived text is not ArrivedAtStore`() {
        assertTrue(
            classifier.classify(node(viewId = "primary_action_button", text = "Confirm pickup"))
                    !is ClickInfo.ArrivedAtStore
        )
    }

    @Test
    fun `'Arrived at store' text on wrong button id is not ArrivedAtStore`() {
        assertTrue(
            classifier.classify(node(viewId = "some_button", text = "Arrived at store"))
                    !is ClickInfo.ArrivedAtStore
        )
    }

    @Test
    fun `null id with Arrived text is not ArrivedAtStore`() {
        assertTrue(
            classifier.classify(node(viewId = null, text = "Arrived at store"))
                    !is ClickInfo.ArrivedAtStore
        )
    }

    @Test
    fun `primary_action_button with null text is not ArrivedAtStore`() {
        assertTrue(
            classifier.classify(node(viewId = "primary_action_button", text = null))
                    !is ClickInfo.ArrivedAtStore
        )
    }
}
