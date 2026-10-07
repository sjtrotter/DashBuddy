package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Notification extraction adapter. The generic dispatcher contract lives in [ScreenRulesetTest].
 */
class NotificationRulesetTest {

    private fun raw(title: String? = null, text: String? = null, bigText: String? = null) =
        RawNotificationData(
            title = title, text = text, bigText = bigText, tickerText = null,
            packageName = "com.doordash.driverapp", postTime = 0L, isClearable = false,
        )

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

}
