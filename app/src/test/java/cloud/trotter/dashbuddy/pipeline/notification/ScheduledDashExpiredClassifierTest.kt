package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [NotificationClassifier] → [NotificationInfo.ScheduledDashExpired].
 */
class ScheduledDashExpiredClassifierTest {

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
    fun `classifies scheduled dash expired notification`() {
        val result = classifier.classify(
            raw(title = "Scheduled Dash", text = "Your scheduled dash has expired")
        )
        assertEquals(NotificationInfo.ScheduledDashExpired, result)
    }

    @Test
    fun `classifies when both keywords appear anywhere in full text`() {
        val result = classifier.classify(
            raw(title = "DoorDash", text = "The scheduled dash you booked has expired")
        )
        assertEquals(NotificationInfo.ScheduledDashExpired, result)
    }

    @Test
    fun `classifies case-insensitively`() {
        val result = classifier.classify(
            raw(title = "SCHEDULED DASH", text = "EXPIRED")
        )
        assertEquals(NotificationInfo.ScheduledDashExpired, result)
    }

    @Test
    fun `scheduled without expired is not ScheduledDashExpired`() {
        val result = classifier.classify(
            raw(title = "Scheduled Dash", text = "Your scheduled dash starts in 15 minutes")
        )
        assertTrue(result !is NotificationInfo.ScheduledDashExpired)
    }

    @Test
    fun `expired without scheduled is not ScheduledDashExpired`() {
        val result = classifier.classify(
            raw(title = "DoorDash", text = "Your promo has expired")
        )
        assertTrue(result !is NotificationInfo.ScheduledDashExpired)
    }
}
