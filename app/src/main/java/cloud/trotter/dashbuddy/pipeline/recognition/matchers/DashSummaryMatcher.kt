package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.util.UtilityFunctions // Assuming this is an object or class
import cloud.trotter.dashbuddy.log.Logger as Log
import java.util.regex.Pattern

class DashSummaryMatcher : ScreenMatcher {

    override val targetScreen = Screen.DASH_SUMMARY_SCREEN
    override val priority = 1

    // Patterns for parsing
    private val timePattern = Pattern.compile("(\\d+)\\s*(hr|min)")
    private val offersPattern =
        Pattern.compile("(\\d+)\\s*out of\\s*(\\d+)", Pattern.CASE_INSENSITIVE)

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // --- 1. MATCHING LOGIC ---

        val hasTitle = root.findNode { it.text.equals("Dash summary", ignoreCase = true) } != null

        val hasDoneButton = root.findNode {
            it.viewIdResourceName?.endsWith("textView_prism_button_title") == true &&
                    it.text.equals("Done", ignoreCase = true)
        } != null

        if (!hasTitle || !hasDoneButton) return null

        // --- 2. PARSING LOGIC ---

        val allNodes = root.flatten()

        var totalEarnings: Double? = null
        var weeklyEarnings: Double? = null
        var offersAccepted = 0
        var offersTotal = 0
        var durationMillis = 0L

        for (i in allNodes.indices) {
            val node = allNodes[i]

            // 2a. Capture Current Dash Earnings (No ID, starts with $)
            if (totalEarnings == null &&
                node.viewIdResourceName == null &&
                node.text?.startsWith("$") == true && !node.text.contains(
                    "Earnings",
                    ignoreCase = true
                )
            ) {
                // Using your existing utility function
                totalEarnings = UtilityFunctions.parseCurrency(node.text)
            }

            // 2b. Capture Stats Rows (Name / Value pairs)
            if (node.viewIdResourceName?.endsWith("name") == true) {
                val label = node.text ?: ""
                val valueNode = allNodes.getOrNull(i + 1)

                if (valueNode?.viewIdResourceName?.endsWith("value") == true) {
                    val valueText = valueNode.text ?: ""

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
                            // Using your existing utility function
                            weeklyEarnings = UtilityFunctions.parseCurrency(valueText)
                        }
                    }
                }
            }
        }

        // --- 3. CALCULATE START TIME ---
        val startTime = context.timestamp - durationMillis

        return ScreenInfo.DashSummary(
            screen = Screen.DASH_SUMMARY_SCREEN,
            totalEarnings = totalEarnings,
            weeklyEarnings = weeklyEarnings,
            offersAccepted = offersAccepted,
            offersTotal = offersTotal,
            onlineDurationMillis = durationMillis,
            estimatedStartTime = startTime
        ).also { Log.d("DashSummaryMatcher", "DashSummary: $it") }
    }

    /**
     * Parses "1 hr 16 min" or "45 min" into milliseconds.
     */
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

    private fun UiNode.flatten(): List<UiNode> {
        val result = mutableListOf<UiNode>()
        result.add(this)
        this.children.forEach { result.addAll(it.flatten()) }
        return result
    }
}