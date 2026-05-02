package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Regression tests for [NotificationClassifier] producing `scheduled_dash_expired` intent.
 */
class ScheduledDashExpiredClassifierTest {

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
    fun `classifies scheduled dash expired notification`() {
        val result = classifier.classify(
            raw(title = "Scheduled Dash", text = "Your scheduled dash has expired")
        )
        assertEquals("scheduled_dash_expired", (result.parsed as ParsedFields.ClickFields).intent)
    }

    @Test
    fun `classifies when both keywords appear anywhere in full text`() {
        val result = classifier.classify(
            raw(title = "DoorDash", text = "The scheduled dash you booked has expired")
        )
        assertEquals("scheduled_dash_expired", (result.parsed as ParsedFields.ClickFields).intent)
    }

    @Test
    fun `classifies case-insensitively`() {
        val result = classifier.classify(
            raw(title = "SCHEDULED DASH", text = "EXPIRED")
        )
        assertEquals("scheduled_dash_expired", (result.parsed as ParsedFields.ClickFields).intent)
    }

    @Test
    fun `scheduled without expired is not ScheduledDashExpired`() {
        val result = classifier.classify(
            raw(title = "Scheduled Dash", text = "Your scheduled dash starts in 15 minutes")
        )
        assertTrue((result.parsed as ParsedFields.ClickFields).intent != "scheduled_dash_expired")
    }

    @Test
    fun `expired without scheduled is not ScheduledDashExpired`() {
        val result = classifier.classify(
            raw(title = "DoorDash", text = "Your promo has expired")
        )
        assertTrue((result.parsed as ParsedFields.ClickFields).intent != "scheduled_dash_expired")
    }
}
