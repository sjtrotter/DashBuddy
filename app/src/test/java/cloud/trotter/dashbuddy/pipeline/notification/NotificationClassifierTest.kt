package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NotificationClassifier].
 *
 * Strings are taken from real notification payloads collected during field sessions.
 * Add new test cases here whenever an UNKNOWN notification is identified in the logs.
 */
class NotificationClassifierTest {

    private val classifier = NotificationClassifier()

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
        assertTrue(result is NotificationInfo.AdditionalTip)
        val tip = result as NotificationInfo.AdditionalTip
        assertEquals(5.00, tip.amount, 0.001)
        assertEquals("H-E-B", tip.storeName)
        assertEquals("4/26, 3:15 PM", tip.deliveredAt)
    }

    @Test
    fun `classifies tip from text field`() {
        val result = classifier.classify(
            raw(
                title = "Tip Added",
                text = "added \$8.00 tip on a past Pizza Hut order delivered at 4/26, 2:40 PM"
            )
        )
        assertTrue(result is NotificationInfo.AdditionalTip)
        val tip = result as NotificationInfo.AdditionalTip
        assertEquals(8.00, tip.amount, 0.001)
        assertEquals("Pizza Hut", tip.storeName)
    }

    @Test
    fun `classifies tip with multi-word store name`() {
        val result = classifier.classify(
            raw(
                bigText = "added \$20.85 tip on a past H-E-B Grocery order delivered at 4/26, 2:08 PM"
            )
        )
        val tip = result as NotificationInfo.AdditionalTip
        assertEquals(20.85, tip.amount, 0.001)
        assertEquals("H-E-B Grocery", tip.storeName)
    }

    @Test
    fun `classifies tip with large amount`() {
        val result = classifier.classify(
            raw(
                bigText = "added \$25.53 tip on a past H-E-B order delivered at 4/26, 7:16 PM"
            )
        )
        val tip = result as NotificationInfo.AdditionalTip
        assertEquals(25.53, tip.amount, 0.001)
    }

    @Test
    fun `classifies tip with PM time`() {
        val result = classifier.classify(
            raw(bigText = "added \$4.50 tip on a past Little Caesars order delivered at 4/26, 8:29 PM")
        )
        val tip = result as NotificationInfo.AdditionalTip
        assertEquals("4/26, 8:29 PM", tip.deliveredAt)
    }

    // =========================================================================
    // NewOrder
    // =========================================================================

    @Test
    fun `classifies new order notification`() {
        val result = classifier.classify(
            raw(title = "New Order", text = "A new delivery order is available")
        )
        assertEquals(NotificationInfo.NewOrder, result)
    }

    @Test
    fun `classifies new order — title case insensitive`() {
        val result = classifier.classify(
            raw(title = "new order", text = "Tap to view")
        )
        assertEquals(NotificationInfo.NewOrder, result)
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
        assertEquals(NotificationInfo.ScheduledDashExpired, result)
    }

    // =========================================================================
    // Unknown
    // =========================================================================

    @Test
    fun `returns Unknown for unrecognized notification`() {
        val result = classifier.classify(
            raw(title = "DoorDash", text = "Something we haven't seen before")
        )
        assertTrue(result is NotificationInfo.Unknown)
    }

    @Test
    fun `Unknown preserves raw text for analysis`() {
        val result = classifier.classify(
            raw(title = "Peak Pay", text = "\$3 boost active in your zone")
        )
        val unknown = result as NotificationInfo.Unknown
        assertTrue(unknown.rawText.contains("Peak Pay") || unknown.rawText.contains("boost"))
    }

    @Test
    fun `returns Unknown when all fields are null`() {
        val result = classifier.classify(
            raw(title = null, text = null, bigText = null, tickerText = null)
        )
        assertTrue(result is NotificationInfo.Unknown)
    }

    @Test
    fun `tip pattern does not match partial text`() {
        // "tip" appears but without the full "added $X.XX tip on a past..." pattern
        val result = classifier.classify(
            raw(title = "DoorDash", text = "You received a tip on your order")
        )
        // Should NOT classify as AdditionalTip
        assertTrue(result !is NotificationInfo.AdditionalTip)
    }
}
