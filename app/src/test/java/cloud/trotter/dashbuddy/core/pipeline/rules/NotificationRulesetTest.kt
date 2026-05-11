package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Ruleset.matchFirst] with notification rules.
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
        intent: String,
        predicate: (RawNotificationData) -> Boolean,
    ) = CompiledRule<RawNotificationData>(
        id = id, priority = priority, overrideable = true,
        branches = listOf(
            CompiledBranch(predicate = predicate, intent = intent),
        ),
    )

    // =========================================================================
    // Basic matching
    // =========================================================================

    @Test
    fun `matchFirst returns null when no rule matches`() {
        val ruleset = Ruleset(
            listOf(rule("r1", 10, "new_order") { raw ->
                raw.title?.contains("New Order") == true
            })
        )
        assertNull(ruleset.matchFirst(raw(title = "DoorDash")))
    }

    @Test
    fun `matchFirst returns first matching result`() {
        val ruleset = Ruleset(
            listOf(rule("r1", 10, "new_order") { true })
        )
        assertEquals("new_order", ruleset.matchFirst(raw())?.intent)
    }

    // =========================================================================
    // Priority order (ascending: lower number = evaluated first)
    // =========================================================================

    @Test
    fun `lower priority rule wins when both match`() {
        val ruleset = Ruleset(
            listOf(
                rule("high-priority", 10, "new_order") { true },
                rule("low-priority", 20, "scheduled_dash_expired") { true },
            )
        )
        // Priority-10 rule evaluated first → returns new_order
        assertEquals("new_order", ruleset.matchFirst(raw())?.intent)
    }

    @Test
    fun `skips non-matching rules and continues to next`() {
        val ruleset = Ruleset(
            listOf(
                rule("r1", 10, "skipped") { false },
                rule("r2", 20, "new_order") { true },
            )
        )
        assertEquals("new_order", ruleset.matchFirst(raw())?.intent)
    }

    @Test
    fun `rules are evaluated in ascending priority regardless of insertion order`() {
        val ruleset = Ruleset(
            listOf(
                rule("last", 30, "scheduled_dash_expired") { true },
                rule("first", 10, "skipped") { false },
                rule("second", 20, "new_order") { true },
            )
        )
        // Priority 10 doesn't match, priority 20 matches → new_order wins
        assertEquals("new_order", ruleset.matchFirst(raw())?.intent)
    }

    // =========================================================================
    // Notification with extraction via parser
    // =========================================================================

    @Test
    fun `notification rule with parser returns extracted fields`() {
        val regex = Regex("""added \$(\d+\.\d{2}) tip on a past (.+?) order delivered at (.*)""")
        val ruleset = Ruleset(
            listOf(
                CompiledRule<RawNotificationData>(
                    id = "tip", priority = 10, overrideable = true,
                    branches = listOf(
                        CompiledBranch(
                            predicate = { raw -> regex.containsMatchIn(raw.toFullString()) },
                            intent = "additional_tip",
                            parser = { raw, _ ->
                                val m = regex.find(raw.toFullString())
                                if (m != null) mapOf(
                                    "amount" to m.groupValues[1].toDoubleOrNull(),
                                    "storeName" to m.groupValues[2].trim(),
                                    "deliveredAt" to m.groupValues[3].trim(),
                                ) else emptyMap()
                            },
                        ),
                    ),
                ),
            )
        )
        val result = ruleset.matchFirst(
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
        assertNull(Ruleset<RawNotificationData>(emptyList()).matchFirst(raw()))
    }

    @Test
    fun `ruleCount reflects number of compiled rules`() {
        val ruleset = Ruleset(
            listOf(
                rule("r1", 10, "a") { false },
                rule("r2", 20, "b") { false },
                rule("r3", 30, "c") { false },
            )
        )
        assertEquals(3, ruleset.ruleCount)
    }
}
