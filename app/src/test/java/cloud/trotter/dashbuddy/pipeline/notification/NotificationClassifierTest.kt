package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.pipeline.Observation
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.rules.JsonRuleInterpreter
import cloud.trotter.dashbuddy.test.util.TestRulesetFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Unit tests for [NotificationClassifier].
 *
 * Strings are taken from real notification payloads collected during field sessions.
 * Add new test cases here whenever an UNKNOWN notification is identified in the logs.
 */
class NotificationClassifierTest {

    private val classifier = NotificationClassifier(mock<JsonRuleInterpreter> {
        on { notificationRuleset } doReturn TestRulesetFactory.notificationRuleset
    })

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun raw(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        tickerText: String? = null,
    ) = RawNotificationData(
        title = title,
        text = text,
        tickerText = tickerText,
        bigText = bigText,
        packageName = "com.doordash.driverapp",
        postTime = System.currentTimeMillis(),
        isClearable = true,
    )

    /** Extract [ParsedFields.NotificationFields] from a classification result. */
    private fun Observation.Notification.notifFields(): ParsedFields.NotificationFields =
        parsed as ParsedFields.NotificationFields

    // =========================================================================
    // AdditionalTip
    // =========================================================================

    @Test
    fun `classifies tip from bigText — standard format`() {
        val result = classifier.classify(
            raw(
                title = "DoorDash",
                bigText = "added \$5.00 tip on a past H-E-B order delivered at 4/26, 3:15 PM"
            )
        )
        val fields = result.notifFields()
        assertEquals("additional_tip", fields.intent)
        assertEquals(5.00, fields.amount!!, 0.001)
        assertEquals("H-E-B", fields.storeName)
        assertEquals("4/26, 3:15 PM", fields.deliveredAt)
    }

    @Test
    fun `classifies tip from text field`() {
        val result = classifier.classify(
            raw(
                title = "Tip Added",
                text = "added \$8.00 tip on a past Pizza Hut order delivered at 4/26, 2:40 PM"
            )
        )
        val fields = result.notifFields()
        assertEquals("additional_tip", fields.intent)
        assertEquals(8.00, fields.amount!!, 0.001)
        assertEquals("Pizza Hut", fields.storeName)
    }

    @Test
    fun `classifies tip with multi-word store name`() {
        val result = classifier.classify(
            raw(
                bigText = "added \$20.85 tip on a past H-E-B Grocery order delivered at 4/26, 2:08 PM"
            )
        )
        val fields = result.notifFields()
        assertEquals("additional_tip", fields.intent)
        assertEquals(20.85, fields.amount!!, 0.001)
        assertEquals("H-E-B Grocery", fields.storeName)
    }

    @Test
    fun `classifies tip with large amount`() {
        val result = classifier.classify(
            raw(
                bigText = "added \$25.53 tip on a past H-E-B order delivered at 4/26, 7:16 PM"
            )
        )
        val fields = result.notifFields()
        assertEquals("additional_tip", fields.intent)
        assertEquals(25.53, fields.amount!!, 0.001)
    }

    @Test
    fun `classifies tip with PM time`() {
        val result = classifier.classify(
            raw(bigText = "added \$4.50 tip on a past Little Caesars order delivered at 4/26, 8:29 PM")
        )
        val fields = result.notifFields()
        assertEquals("additional_tip", fields.intent)
        assertEquals("4/26, 8:29 PM", fields.deliveredAt)
    }

    // =========================================================================
    // NewOrder
    // =========================================================================

    @Test
    fun `classifies new order notification`() {
        val result = classifier.classify(
            raw(title = "New Order", text = "A new delivery order is available")
        )
        assertEquals("new_order", result.notifFields().intent)
    }

    @Test
    fun `classifies new order — title case insensitive`() {
        val result = classifier.classify(
            raw(title = "new order", text = "Tap to view")
        )
        assertEquals("new_order", result.notifFields().intent)
    }

    // =========================================================================
    // ScheduledDashExpired
    // =========================================================================

    @Test
    fun `classifies scheduled dash expired`() {
        val result = classifier.classify(
            raw(
                title = "Scheduled Dash",
                text = "Your scheduled dash has expired"
            )
        )
        assertEquals("scheduled_dash_expired", result.notifFields().intent)
    }

    // =========================================================================
    // Unknown
    // =========================================================================

    @Test
    fun `returns Unknown for unrecognized notification`() {
        val result = classifier.classify(
            raw(title = "DoorDash", text = "Something we haven't seen before")
        )
        assertEquals("unknown", result.notifFields().intent)
    }

    @Test
    fun `Unknown preserves raw text for analysis`() {
        val result = classifier.classify(
            raw(title = "Peak Pay", text = "\$3 boost active in your zone")
        )
        val fields = result.notifFields()
        assertEquals("unknown", fields.intent)
        assertNotNull(fields.rawText)
        assertTrue(fields.rawText!!.contains("Peak Pay") || fields.rawText!!.contains("boost"))
    }

    @Test
    fun `returns Unknown when all fields are null`() {
        val result = classifier.classify(
            raw(title = null, text = null, bigText = null, tickerText = null)
        )
        assertEquals("unknown", result.notifFields().intent)
    }

    @Test
    fun `tip pattern does not match partial text`() {
        // "tip" appears but without the full "added $X.XX tip on a past..." pattern
        val result = classifier.classify(
            raw(title = "DoorDash", text = "You received a tip on your order")
        )
        // Should NOT classify as additional_tip
        assertTrue(result.notifFields().intent != "additional_tip")
    }
}
