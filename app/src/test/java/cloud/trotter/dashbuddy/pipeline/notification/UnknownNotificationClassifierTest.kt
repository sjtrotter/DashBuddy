package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadataProvider
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Regression tests for [NotificationClassifier] producing `unknown` intent.
 *
 * These are real notification payloads observed in session logs that have not yet
 * been promoted to a first-class intent type. When a pattern is understood and a new
 * intent is added, move the corresponding test to that type's test file.
 */
class UnknownNotificationClassifierTest {

    private val classifier = NotificationClassifier(
        mock<JsonRuleInterpreter>(),
        mock<ReplayMetadataProvider> { on { current() } doReturn ReplayMetadata.EMPTY },
    )

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
    fun `all-null notification is unknown`() {
        val result = classifier.classify(raw())
        assertEquals("unknown", (result.parsed as ParsedFields.NotificationFields).intent)
    }

    @Test
    fun `unknown preserves raw text for analysis`() {
        val result = classifier.classify(raw(title = "Peak Pay", text = "\$3 boost in your zone"))
        val fields = result.parsed as ParsedFields.NotificationFields
        assertEquals("unknown", fields.intent)
        assertTrue(fields.rawText!!.isNotBlank())
    }

    @Test
    fun `peak pay notification is currently unknown`() {
        // Not yet classified — add a peak_pay intent when observed in more detail
        val result = classifier.classify(raw(title = "Peak Pay", text = "\$3 boost active in your zone"))
        assertEquals("unknown", (result.parsed as ParsedFields.NotificationFields).intent)
    }

    @Test
    fun `generic DoorDash notification is unknown`() {
        val result = classifier.classify(raw(title = "DoorDash", text = "Something we haven't seen before"))
        assertEquals("unknown", (result.parsed as ParsedFields.NotificationFields).intent)
    }

    @Test
    fun `transfer notification is currently unknown`() {
        // Crimson transfer — not yet classified; captured raw for future transfer intent
        val result = classifier.classify(
            raw(title = "DoorDash", text = "Your \$55.42 transfer was initiated | Visa ████6222")
        )
        assertEquals("unknown", (result.parsed as ParsedFields.NotificationFields).intent)
    }

    @Test
    fun `unknown raw text joins all non-null fields`() {
        val result = classifier.classify(raw(title = "DoorDash", text = "Some text"))
        val fields = result.parsed as ParsedFields.NotificationFields
        assertEquals("unknown", fields.intent)
        assertTrue(fields.rawText!!.contains("DoorDash"))
        assertTrue(fields.rawText!!.contains("Some text"))
    }
}
