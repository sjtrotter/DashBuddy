package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Regression tests for [NotificationClassifier] producing `new_order` intent.
 */
class NewOrderClassifierTest {

    private val classifier = NotificationClassifier(mock<JsonRuleInterpreter>())

    private fun raw(title: String? = null, text: String? = null) =
        RawNotificationData(
            title = title,
            text = text,
            bigText = null,
            tickerText = null,
            packageName = "com.doordash.driverapp",
            postTime = System.currentTimeMillis(),
            isClearable = true,
        )

    @Test
    fun `classifies 'New Order' title`() {
        val result = classifier.classify(raw(title = "New Order"))
        assertEquals("new_order", (result.parsed as ParsedFields.ClickFields).intent)
    }

    @Test
    fun `classifies new order case-insensitively`() {
        val result = classifier.classify(raw(title = "new order"))
        assertEquals("new_order", (result.parsed as ParsedFields.ClickFields).intent)
    }

    @Test
    fun `classifies NEW ORDER all caps`() {
        val result = classifier.classify(raw(title = "NEW ORDER"))
        assertEquals("new_order", (result.parsed as ParsedFields.ClickFields).intent)
    }

    @Test
    fun `notification without 'new order' in title is not NewOrder`() {
        val result = classifier.classify(raw(title = "DoorDash", text = "A new order is here"))
        // "new order" is in text, not title — should not match new_order
        assertTrue((result.parsed as ParsedFields.ClickFields).intent != "new_order")
    }

    @Test
    fun `null title is not NewOrder`() {
        val result = classifier.classify(raw(title = null, text = "New Order available"))
        assertTrue((result.parsed as ParsedFields.ClickFields).intent != "new_order")
    }
}
