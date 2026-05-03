package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        classify: (RawNotificationData) -> NotificationClassifyResult?,
    ) = CompiledNotificationRule(id = id, priority = priority, overrideable = true, classify = classify)

    // =========================================================================
    // Basic matching
    // =========================================================================

    @Test
    fun `classifyFirst returns null when no rule matches`() {
        val ruleset = NotificationRuleset(
            listOf(rule("r1", 10) { raw ->
                if (raw.title?.contains("New Order") == true) NotificationClassifyResult("new_order") else null
            })
        )
        assertNull(ruleset.classifyFirst(raw(title = "DoorDash")))
    }

    @Test
    fun `classifyFirst returns first non-null classify result`() {
        val ruleset = NotificationRuleset(
            listOf(rule("r1", 10) { NotificationClassifyResult("new_order") })
        )
        assertEquals("new_order", ruleset.classifyFirst(raw())?.intent)
    }

    // =========================================================================
    // Priority order (ascending: lower number = evaluated first)
    // =========================================================================

    @Test
    fun `lower priority rule wins when both match`() {
        val ruleset = NotificationRuleset(
            listOf(
                rule("high-priority", 10) { NotificationClassifyResult("new_order") },
                rule("low-priority", 20) { NotificationClassifyResult("scheduled_dash_expired") },
            )
        )
        // Priority-10 rule evaluated first → returns new_order
        assertEquals("new_order", ruleset.classifyFirst(raw())?.intent)
    }

    @Test
    fun `skips null-returning rules and continues to next`() {
        val ruleset = NotificationRuleset(
            listOf(
                rule("r1", 10) { null },  // always returns null
                rule("r2", 20) { NotificationClassifyResult("new_order") },
            )
        )
        assertEquals("new_order", ruleset.classifyFirst(raw())?.intent)
    }

    @Test
    fun `rules are evaluated in ascending priority regardless of insertion order`() {
        val ruleset = NotificationRuleset(
            listOf(
                rule("last", 30) { NotificationClassifyResult("scheduled_dash_expired") },
                rule("first", 10) { null },  // always returns null
                rule("second", 20) { NotificationClassifyResult("new_order") },
            )
        )
        // Priority 10 returns null, priority 20 returns new_order → new_order wins
        assertEquals("new_order", ruleset.classifyFirst(raw())?.intent)
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
                NotificationClassifyResult(
                    intent = "additional_tip",
                    fields = mapOf(
                        "amount" to amount,
                        "storeName" to m.groupValues[2].trim(),
                        "deliveredAt" to m.groupValues[3].trim(),
                    ),
                )
            })
        )
        val result = ruleset.classifyFirst(
            raw(bigText = "added \$5.00 tip on a past H-E-B order delivered at 4/26, 3:15 PM")
        )
        assertNotNull(result)
        assertEquals("additional_tip", result!!.intent)
        assertEquals(5.00, result.fields["amount"] as Double, 0.001)
        assertEquals("H-E-B", result.fields["storeName"])
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
