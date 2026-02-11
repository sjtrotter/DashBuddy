package cloud.trotter.dashbuddy.pipeline.accessibility.screen.matchers

import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import java.util.regex.Pattern
import javax.inject.Inject

class DashSummaryMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.DASH_SUMMARY_SCREEN
    override val priority = 1

    private val timePattern = Pattern.compile("(\\d+)\\s*(hr|min)")
    private val offersPattern =
        Pattern.compile("(\\d+)\\s*out of\\s*(\\d+)", Pattern.CASE_INSENSITIVE)

    private val tag = "DashSummaryMatcher"

    override fun matches(node: UiNode): ScreenInfo? {

        // 1. Identification
        // Look for "Dash summary" title AND "Done" button
        val hasTitle = node.hasNode { it.text.equals("Dash summary", ignoreCase = true) }
        val hasDoneButton = node.hasNode {
            it.hasId("textView_prism_button_title") && it.text.equals("Done", ignoreCase = true)
        }

        if (!hasTitle || !hasDoneButton) return null

        Timber.d("Context found (Title & Done Button). Analyzing...")

        // 2. Extraction - Total Pay
        // Targeted search for ID 'header_pay' which contains the Dash Total
        val payNode = node.findDescendantById("header_pay")
        val totalEarnings = payNode?.text?.let { UtilityFunctions.parseCurrency(it) }

        Timber.d("Dash Earnings: node='${payNode?.text}' -> parsed=$totalEarnings")

        // 3. Extraction - Stats Rows
        // We look for any node with ID 'name' (the label) and try to find its sibling 'value'
        var durationMillis = 0L
        var offersAccepted = 0
        var offersTotal = 0
        var weeklyEarnings: Double? = null

        // Find all label nodes
        val labelNodes = node.findNodes { it.hasId("name") }

        for (labelNode in labelNodes) {
            val label = labelNode.text ?: ""
            // The value node is usually a sibling in the same parent container
            val parent = labelNode.parent ?: continue
            val valueNode = parent.findChildById("value") ?: continue
            val valueText = valueNode.text ?: ""

            Timber.d("Found Row: '$label' -> '$valueText'")

            when {
                label.equals("Total online time", ignoreCase = true) -> {
                    durationMillis = parseDurationToMillis(valueText)
                }

                label.equals("Offers accepted", ignoreCase = true) -> {
                    val (accepted, total) = parseOffers(valueText)
                    offersAccepted = accepted
                    offersTotal = total
                }

                label.equals("Earnings this week", ignoreCase = true) -> {
                    weeklyEarnings = UtilityFunctions.parseCurrency(valueText)
                }
            }
        }

        Timber.d(
            "Parsed Stats: Time=${durationMillis}ms, Offers=$offersAccepted/$offersTotal, Weekly=$weeklyEarnings"
        )

        // 4. Sanity Check
        // The current dash earnings cannot exceed the weekly earnings (since it includes them).
        if (totalEarnings != null && weeklyEarnings != null) {
            // Allow small delta (0.02) for floating point math weirdness,
            // but generally Total must be <= Weekly.
            if (totalEarnings > (weeklyEarnings + 0.02)) {
                Timber.w(
                    "Sanity Check FAILED: Dash Total ($totalEarnings) > Weekly Total ($weeklyEarnings). Ignoring invalid data."
                )
                return null
            }
        }

        val startTime = System.currentTimeMillis() - durationMillis

        return ScreenInfo.DashSummary(
            screen = Screen.DASH_SUMMARY_SCREEN,
            totalEarnings = totalEarnings,
            weeklyEarnings = weeklyEarnings,
            offersAccepted = offersAccepted,
            offersTotal = offersTotal,
            onlineDurationMillis = durationMillis,
            estimatedStartTime = startTime
        ).also { Timber.i("Match Success: $it") }
    }

    private fun parseDurationToMillis(text: String): Long {
        var totalMillis = 0L
        val matcher = timePattern.matcher(text)
        while (matcher.find()) {
            val value = matcher.group(1)?.toLongOrNull() ?: 0L
            val unit = matcher.group(2) // "hr" or "min"

            if (unit == "hr") {
                totalMillis += value * 3600 * 1000
            } else if (unit == "min") {
                totalMillis += value * 60 * 1000
            }
        }
        return totalMillis
    }

    private fun parseOffers(text: String): Pair<Int, Int> {
        val matcher = offersPattern.matcher(text)
        if (matcher.find()) {
            val accepted = matcher.group(1)?.toIntOrNull() ?: 0
            val total = matcher.group(2)?.toIntOrNull() ?: 0
            return Pair(accepted, total)
        }
        return Pair(0, 0)
    }
}