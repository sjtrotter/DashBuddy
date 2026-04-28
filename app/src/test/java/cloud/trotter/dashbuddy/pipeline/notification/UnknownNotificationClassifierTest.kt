package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [NotificationClassifier] → [NotificationInfo.Unknown].
 *
 * These are real notification payloads observed in session logs that have not yet
 * been promoted to a first-class subtype. When a pattern is understood and a new
 * subtype is added, move the corresponding test to that type's test file.
 */
class UnknownNotificationClassifierTest {

    private val classifier = NotificationClassifier()

    private fun raw(title: String? = null, text: String? = null, bigText: String? = null) =
        RawNotificationData(
            title = title,
            text = text,
            bigText = bigText,
            tickerText = null,
            packageName = "com.doordash.driverapp",
            postTime = System.currentTimeMillis(),
            isClearable = true,
        )

    @Test
    fun `all-null notification is Unknown`() {
        val result = classifier.classify(raw())
        assertTrue(result is NotificationInfo.Unknown)
    }

    @Test
    fun `Unknown preserves raw text for analysis`() {
        val result = classifier.classify(raw(title = "Peak Pay", text = "\$3 boost in your zone"))
        val unknown = result as NotificationInfo.Unknown
        assertTrue(unknown.rawText.isNotBlank())
    }

    @Test
    fun `peak pay notification is currently Unknown`() {
        // Not yet classified — add a PeakPayAvailable subtype when observed in more detail
        val result = classifier.classify(raw(title = "Peak Pay", text = "\$3 boost active in your zone"))
        assertTrue(result is NotificationInfo.Unknown)
    }

    @Test
    fun `generic DoorDash notification is Unknown`() {
        val result = classifier.classify(raw(title = "DoorDash", text = "Something we haven't seen before"))
        assertTrue(result is NotificationInfo.Unknown)
    }

    @Test
    fun `transfer notification is currently Unknown`() {
        // Crimson transfer — not yet classified; captured raw for future TRANSFER_COMPLETE subtype
        val result = classifier.classify(
            raw(title = "DoorDash", text = "Your \$55.42 transfer was initiated | Visa ████6222")
        )
        assertTrue(result is NotificationInfo.Unknown)
    }

    @Test
    fun `Unknown raw text joins all non-null fields`() {
        val result = classifier.classify(raw(title = "DoorDash", text = "Some text"))
        val unknown = result as NotificationInfo.Unknown
        assertTrue(unknown.rawText.contains("DoorDash"))
        assertTrue(unknown.rawText.contains("Some text"))
    }
}
