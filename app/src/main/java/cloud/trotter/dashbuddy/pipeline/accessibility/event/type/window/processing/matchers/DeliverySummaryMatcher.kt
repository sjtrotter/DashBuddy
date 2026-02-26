package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.data.pay.PayParser
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.Screen
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs

class DeliverySummaryMatcher @Inject constructor(
    private val payParser: PayParser
) : ScreenMatcher {

    // We default to identifying as EXPANDED if we find data, but the object itself carries the truth.
    override val targetScreen: Screen = Screen.DELIVERY_SUMMARY_EXPANDED
    override val priority: Int = 20

    override fun matches(node: UiNode): ScreenInfo? {
        // 1. Context Check
        val hasContext = node.hasNode {
            val text = it.text ?: ""
            text.contains("This offer", ignoreCase = false) ||
                    text.contains("Delivery Complete", ignoreCase = true)
        }
        if (!hasContext) return null

        // 2. Locate Containers
        val earningsContainer = node.findNodes { it.matchesId("earnings_container") }
            .find { container ->
                container.hasNode { it.text?.contains("This offer", ignoreCase = true) == true }
            }
        val searchScope = earningsContainer ?: node

        // 3. Extract Common Data (Available in both states)
        val finalValueNode = earningsContainer?.findDescendantById("final_value")
            ?: node.findDescendantById("final_value")
        val headlineTotal = finalValueNode?.text?.replace("$", "")?.toDoubleOrNull() ?: 0.0

        // Extract "Data on the table" (Session Earnings)
        val sessionEarningsNode = node.findDescendantById("earnings_ticker")
        val sessionEarnings = parseTickerText(sessionEarningsNode)

        // 4. Check State (Expanded vs Collapsed)
        val hasDoorDashPay = searchScope.hasNode { it.matchesText("DoorDash pay") }
        val hasTips = searchScope.hasNode { it.matchesText("Customer tips") }
        val isVisuallyExpanded = hasDoorDashPay && hasTips

        // 5. Parse Breakdown (if expanded)
        var parsedPay = if (isVisuallyExpanded) payParser.parsePayFromTree(searchScope) else null

        // 6. Validation / Glitch Detection
        // If we think it's expanded, but the math doesn't check out, treat it as Collapsed (Glitch).
        if (parsedPay != null && headlineTotal > 0) {
            val diff = abs(parsedPay.total - headlineTotal)
            if (diff > 0.02) {
                Timber.w("Validation Failed: Breakdown ${parsedPay.total} != Header $headlineTotal. Treating as Glitch.")
                parsedPay = null // Force fallback to button logic
            }
        }

        // 7. Find Button (Only strictly needed if we don't have the parsed pay)
        val expandButton =
            if (parsedPay == null) findExpandButton(node, earningsContainer) else null

        // 8. Return Unified Object
        return ScreenInfo.DeliverySummary(
            // Use the enum to indicate "Logical State" for the rest of the app
            screen = if (parsedPay != null) Screen.DELIVERY_SUMMARY_EXPANDED else Screen.DELIVERY_SUMMARY_COLLAPSED,
            isExpanded = parsedPay != null,
            totalPay = headlineTotal,
            parsedPay = parsedPay,
            expandButton = expandButton,
            sessionEarnings = sessionEarnings
        )
    }

    private fun parseTickerText(tickerNode: UiNode?): Double? {
        // Tickers usually have separate TextViews for digits: "3" "4" "." "5" "0"
        // We collect all text from children and join them.
        return tickerNode?.allText?.joinToString("")?.replace("$", "")?.toDoubleOrNull()
    }

    private fun findExpandButton(root: UiNode, container: UiNode?): UiNode? {
        return container?.findDescendantById("expandable_view")
            ?: container?.findDescendantById("expandable_layout")
            ?: root.findNodes { it.matchesId("expandable_view") }.lastOrNull()
    }
}