package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import java.util.regex.Pattern
import javax.inject.Inject

class DashSummaryParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.DASH_SUMMARY_SCREEN

    private val timePattern = Pattern.compile("(\\d+)\\s*(hr|min)")
    private val offersPattern = Pattern.compile("(\\d+)\\s*out of\\s*(\\d+)", Pattern.CASE_INSENSITIVE)

    override fun parse(node: UiNode): ScreenInfo {
        Timber.d("DashSummaryParser: analyzing node tree...")

        val payNode = node.findDescendantById("header_pay")
        val totalEarnings = payNode?.text?.let { UtilityFunctions.parseCurrency(it) }
        Timber.d("Dash Earnings: node='${payNode?.text}' -> parsed=$totalEarnings")

        var durationMillis = 0L
        var offersAccepted = 0
        var offersTotal = 0
        var weeklyEarnings: Double? = null

        val labelNodes = node.findNodes { it.hasId("name") }
        for (labelNode in labelNodes) {
            val label = labelNode.text ?: ""
            val parent = labelNode.parent ?: continue
            val valueNode = parent.findChildById("value") ?: continue
            val valueText = valueNode.text ?: ""
            Timber.d("Found Row: '$label' -> '$valueText'")

            when {
                label.equals("Total online time", ignoreCase = true) ->
                    durationMillis = parseDurationToMillis(valueText)
                label.equals("Offers accepted", ignoreCase = true) -> {
                    val (accepted, total) = parseOffers(valueText)
                    offersAccepted = accepted
                    offersTotal = total
                }
                label.equals("Earnings this week", ignoreCase = true) ->
                    weeklyEarnings = UtilityFunctions.parseCurrency(valueText)
            }
        }

        Timber.d("Parsed Stats: Time=${durationMillis}ms, Offers=$offersAccepted/$offersTotal, Weekly=$weeklyEarnings")

        // Sanity check: dash total should not exceed weekly total.
        if (totalEarnings != null && weeklyEarnings != null && totalEarnings > (weeklyEarnings + 0.02)) {
            Timber.w("Sanity Check FAILED: Dash Total ($totalEarnings) > Weekly Total ($weeklyEarnings). Returning Simple.")
            return ScreenInfo.Simple(targetScreen)
        }

        val startTime = System.currentTimeMillis() - durationMillis
        return ScreenInfo.DashSummary(
            screen = targetScreen,
            totalEarnings = totalEarnings,
            weeklyEarnings = weeklyEarnings,
            offersAccepted = offersAccepted,
            offersTotal = offersTotal,
            onlineDurationMillis = durationMillis,
            estimatedStartTime = startTime
        ).also { Timber.i("DashSummaryParser result: $it") }
    }

    private fun parseDurationToMillis(text: String): Long {
        var totalMillis = 0L
        val matcher = timePattern.matcher(text)
        while (matcher.find()) {
            val value = matcher.group(1)?.toLongOrNull() ?: 0L
            val unit = matcher.group(2)
            if (unit == "hr") totalMillis += value * 3600 * 1000
            else if (unit == "min") totalMillis += value * 60 * 1000
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
