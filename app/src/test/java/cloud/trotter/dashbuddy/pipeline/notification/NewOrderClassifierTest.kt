package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [NotificationClassifier] → [NotificationInfo.NewOrder].
 */
class NewOrderClassifierTest {

    private val classifier = NotificationClassifier()

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
        assertEquals(NotificationInfo.NewOrder, result)
    }

    @Test
    fun `classifies new order case-insensitively`() {
        val result = classifier.classify(raw(title = "new order"))
        assertEquals(NotificationInfo.NewOrder, result)
    }

    @Test
    fun `classifies NEW ORDER all caps`() {
        val result = classifier.classify(raw(title = "NEW ORDER"))
        assertEquals(NotificationInfo.NewOrder, result)
    }

    @Test
    fun `notification without 'new order' in title is not NewOrder`() {
        val result = classifier.classify(raw(title = "DoorDash", text = "A new order is here"))
        // "new order" is in text, not title — should not match NewOrder
        assertTrue(result !is NotificationInfo.NewOrder)
    }

    @Test
    fun `null title is not NewOrder`() {
        val result = classifier.classify(raw(title = null, text = "New Order available"))
        assertTrue(result !is NotificationInfo.NewOrder)
    }
}
