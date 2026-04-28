package cloud.trotter.dashbuddy.pipeline.notification

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for [NotificationClassifier] → [NotificationInfo.AdditionalTip].
 *
 * Each test case corresponds to a real notification payload observed in the field.
 * Add a new test whenever a new tip notification format is found in session logs.
 */
class AdditionalTipClassifierTest {

    private val classifier = NotificationClassifier()

    private fun raw(bigText: String? = null, text: String? = null, title: String? = null) =
        RawNotificationData(
            title = title,
            text = text,
            bigText = bigText,
            tickerText = null,
            packageName = "com.doordash.driverapp",
            postTime = System.currentTimeMillis(),
            isClearable = true,
        )

    private fun assertTip(
        raw: RawNotificationData,
        expectedAmount: Double,
        expectedStore: String,
        expectedDeliveredAt: String,
    ) {
        val result = classifier.classify(raw)
        assertTrue("Expected AdditionalTip, got $result", result is NotificationInfo.AdditionalTip)
        val tip = result as NotificationInfo.AdditionalTip
        assertEquals(expectedAmount, tip.amount, 0.001)
        assertEquals(expectedStore, tip.storeName)
        assertEquals(expectedDeliveredAt, tip.deliveredAt)
    }

    // =========================================================================
    // Amount variants
    // =========================================================================

    @Test
    fun `parses small tip`() = assertTip(
        raw(bigText = "added \$4.50 tip on a past Little Caesars order delivered at 4/26, 8:29 PM"),
        expectedAmount = 4.50,
        expectedStore = "Little Caesars",
        expectedDeliveredAt = "4/26, 8:29 PM",
    )

    @Test
    fun `parses medium tip`() = assertTip(
        raw(bigText = "added \$8.00 tip on a past Pizza Hut order delivered at 4/26, 2:40 PM"),
        expectedAmount = 8.00,
        expectedStore = "Pizza Hut",
        expectedDeliveredAt = "4/26, 2:40 PM",
    )

    @Test
    fun `parses large tip`() = assertTip(
        raw(bigText = "added \$25.53 tip on a past H-E-B order delivered at 4/26, 7:16 PM"),
        expectedAmount = 25.53,
        expectedStore = "H-E-B",
        expectedDeliveredAt = "4/26, 7:16 PM",
    )

    @Test
    fun `parses very large tip`() = assertTip(
        raw(bigText = "added \$20.85 tip on a past H-E-B order delivered at 4/26, 2:08 PM"),
        expectedAmount = 20.85,
        expectedStore = "H-E-B",
        expectedDeliveredAt = "4/26, 2:08 PM",
    )

    @Test
    fun `parses tip with zero cents`() = assertTip(
        raw(bigText = "added \$5.00 tip on a past Chick-fil-A order delivered at 4/25, 12:00 PM"),
        expectedAmount = 5.00,
        expectedStore = "Chick-fil-A",
        expectedDeliveredAt = "4/25, 12:00 PM",
    )

    // =========================================================================
    // Store name variants
    // =========================================================================

    @Test
    fun `parses single-word store name`() = assertTip(
        raw(bigText = "added \$6.34 tip on a past Subway order delivered at 4/26, 3:28 PM"),
        expectedAmount = 6.34,
        expectedStore = "Subway",
        expectedDeliveredAt = "4/26, 3:28 PM",
    )

    @Test
    fun `parses multi-word store name`() = assertTip(
        raw(bigText = "added \$7.00 tip on a past Little Caesars Pizza order delivered at 4/26, 5:30 PM"),
        expectedAmount = 7.00,
        expectedStore = "Little Caesars Pizza",
        expectedDeliveredAt = "4/26, 5:30 PM",
    )

    @Test
    fun `parses store name with ampersand`() = assertTip(
        raw(bigText = "added \$10.00 tip on a past Bath & Body Works order delivered at 4/25, 6:00 PM"),
        expectedAmount = 10.00,
        expectedStore = "Bath & Body Works",
        expectedDeliveredAt = "4/25, 6:00 PM",
    )

    @Test
    fun `parses store name with hyphen`() = assertTip(
        raw(bigText = "added \$5.53 tip on a past H-E-B order delivered at 4/26, 8:01 PM"),
        expectedAmount = 5.53,
        expectedStore = "H-E-B",
        expectedDeliveredAt = "4/26, 8:01 PM",
    )

    // =========================================================================
    // Time variants
    // =========================================================================

    @Test
    fun `parses AM delivery time`() = assertTip(
        raw(bigText = "added \$3.00 tip on a past McDonald's order delivered at 4/26, 9:15 AM"),
        expectedAmount = 3.00,
        expectedStore = "McDonald's",
        expectedDeliveredAt = "4/26, 9:15 AM",
    )

    @Test
    fun `parses single-digit hour`() = assertTip(
        raw(bigText = "added \$4.00 tip on a past Taco Bell order delivered at 4/26, 7:05 PM"),
        expectedAmount = 4.00,
        expectedStore = "Taco Bell",
        expectedDeliveredAt = "4/26, 7:05 PM",
    )

    @Test
    fun `parses noon delivery`() = assertTip(
        raw(bigText = "added \$6.00 tip on a past Chipotle order delivered at 4/26, 12:30 PM"),
        expectedAmount = 6.00,
        expectedStore = "Chipotle",
        expectedDeliveredAt = "4/26, 12:30 PM",
    )

    // =========================================================================
    // Field location (title vs text vs bigText)
    // =========================================================================

    @Test
    fun `classifies tip from text field when no bigText`() = assertTip(
        raw(text = "added \$5.00 tip on a past H-E-B order delivered at 4/26, 3:15 PM"),
        expectedAmount = 5.00,
        expectedStore = "H-E-B",
        expectedDeliveredAt = "4/26, 3:15 PM",
    )

    // =========================================================================
    // Non-matching — should NOT classify as AdditionalTip
    // =========================================================================

    @Test
    fun `generic tip mention without full pattern is not AdditionalTip`() {
        val result = classifier.classify(raw(text = "You received a tip on your order"))
        assertTrue(result !is NotificationInfo.AdditionalTip)
    }

    @Test
    fun `empty notification is not AdditionalTip`() {
        val result = classifier.classify(raw())
        assertTrue(result !is NotificationInfo.AdditionalTip)
    }
}
