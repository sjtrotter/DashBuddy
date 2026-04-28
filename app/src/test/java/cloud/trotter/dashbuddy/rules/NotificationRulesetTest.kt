package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NotificationRuleset.classifyFirst].
 */
class NotificationRulesetTest {

    private fun raw(title: String? = null, text: String? = null, bigText: String? = null) =
        RawNotificationData(
            title = title, text = text, bigText = bigText, tickerText = null,
            packageName = "com.doordash.driverapp", postTime = 0L, isClearable = false,
        )

    private fun rule(
        id: String,
        priority: Int,
        classify: (RawNotificationData) -> NotificationInfo?,
    ) = CompiledNotificationRule(id = id, priority = priority, overrideable = true, classify = classify)

    // =========================================================================
    // Basic matching
    // =========================================================================

    @Test
    fun `classifyFirst returns null when no rule matches`() {
        val ruleset = NotificationRuleset(
            listOf(rule("r1", 10) { raw ->
                if (raw.title?.contains("New Order") == true) NotificationInfo.NewOrder else null
            })
        )
        assertNull(ruleset.classifyFirst(raw(title = "DoorDash")))
    }

    @Test
    fun `classifyFirst returns first non-null classify result`() {
        val ruleset = NotificationRuleset(
            listOf(rule("r1", 10) { NotificationInfo.NewOrder })
        )
        assertEquals(NotificationInfo.NewOrder, ruleset.classifyFirst(raw()))
    }

    // =========================================================================
    // Priority order (ascending: lower number = evaluated first)
    // =========================================================================

    @Test
    fun `lower priority rule wins when both match`() {
        val ruleset = NotificationRuleset(
            listOf(
                rule("high-priority", 10) { NotificationInfo.NewOrder },
                rule("low-priority", 20) { NotificationInfo.ScheduledDashExpired },
            )
        )
        // Priority-10 rule evaluated first → returns NewOrder
        assertEquals(NotificationInfo.NewOrder, ruleset.classifyFirst(raw()))
    }

    @Test
    fun `skips null-returning rules and continues to next`() {
        val ruleset = NotificationRuleset(
            listOf(
                rule("r1", 10) { null },  // always returns null
                rule("r2", 20) { NotificationInfo.NewOrder },
            )
        )
        assertEquals(NotificationInfo.NewOrder, ruleset.classifyFirst(raw()))
    }

    @Test
    fun `rules are evaluated in ascending priority regardless of insertion order`() {
        val ruleset = NotificationRuleset(
            listOf(
                rule("last", 30) { NotificationInfo.ScheduledDashExpired },
                rule("first", 10) { null },  // always returns null
                rule("second", 20) { NotificationInfo.NewOrder },
            )
        )
        // Priority 10 returns null, priority 20 returns NewOrder → NewOrder wins
        assertEquals(NotificationInfo.NewOrder, ruleset.classifyFirst(raw()))
    }

    // =========================================================================
    // AdditionalTip with extraction
    // =========================================================================

    @Test
    fun `AdditionalTip rule returns extracted tip data`() {
        val regex = Regex("""added \$(\d+\.\d{2}) tip on a past (.+?) order delivered at (.*)""")
        val ruleset = NotificationRuleset(
            listOf(rule("tip", 10) { raw ->
                val m = regex.find(raw.toFullString()) ?: return@rule null
                val amount = m.groupValues[1].toDoubleOrNull() ?: return@rule null
                NotificationInfo.AdditionalTip(amount, m.groupValues[2].trim(), m.groupValues[3].trim())
            })
        )
        val result = ruleset.classifyFirst(
            raw(bigText = "added \$5.00 tip on a past H-E-B order delivered at 4/26, 3:15 PM")
        )
        assertTrue(result is NotificationInfo.AdditionalTip)
        val tip = result as NotificationInfo.AdditionalTip
        assertEquals(5.00, tip.amount, 0.001)
        assertEquals("H-E-B", tip.storeName)
    }

    // =========================================================================
    // ruleCount and empty ruleset
    // =========================================================================

    @Test
    fun `empty ruleset returns null`() {
        assertNull(NotificationRuleset(emptyList()).classifyFirst(raw()))
    }

    @Test
    fun `ruleCount reflects number of compiled rules`() {
        val ruleset = NotificationRuleset(
            listOf(
                rule("r1", 10) { null },
                rule("r2", 20) { null },
                rule("r3", 30) { null },
            )
        )
        assertEquals(3, ruleset.ruleCount)
    }
}
