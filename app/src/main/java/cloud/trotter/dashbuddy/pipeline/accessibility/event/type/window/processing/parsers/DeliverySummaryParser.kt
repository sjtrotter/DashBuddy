package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.core.data.pay.PayParser
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs

class DeliverySummaryParser @Inject constructor(
    private val payParser: PayParser
) : ScreenParser {

    // Lookup key matches the matcher's targetScreen.
    override val targetScreen: Screen = Screen.DELIVERY_SUMMARY_EXPANDED

    override fun parse(node: UiNode): ScreenInfo {
        val earningsContainer = node.findNodes { it.matchesId("earnings_container") }
            .find { container ->
                container.hasNode { it.text?.contains("This offer", ignoreCase = true) == true }
            }
        val searchScope = earningsContainer ?: node

        val finalValueNode = earningsContainer?.findDescendantById("final_value")
            ?: node.findDescendantById("final_value")
        val headlineTotal = finalValueNode?.text?.replace("$", "")?.toDoubleOrNull() ?: 0.0

        val sessionEarningsNode = node.findDescendantById("earnings_ticker")
        val sessionEarnings = sessionEarningsNode?.allText?.joinToString("")?.replace("$", "")?.toDoubleOrNull()

        val isVisuallyExpanded = searchScope.hasNode { it.matchesText("DoorDash pay") } &&
                searchScope.hasNode { it.matchesText("Customer tips") }

        var parsedPay = if (isVisuallyExpanded) payParser.parsePayFromTree(searchScope) else null

        if (parsedPay != null && headlineTotal > 0) {
            val diff = abs(parsedPay.total - headlineTotal)
            if (diff > 0.02) {
                Timber.w("Validation Failed: Breakdown ${parsedPay.total} != Header $headlineTotal. Treating as Glitch.")
                parsedPay = null
            }
        }

        val expandButton = if (parsedPay == null) {
            earningsContainer?.findDescendantById("expandable_view")
                ?: earningsContainer?.findDescendantById("expandable_layout")
                ?: node.findNodes { it.matchesId("expandable_view") }.lastOrNull()
        } else null

        return ScreenInfo.DeliverySummary(
            screen = if (parsedPay != null) Screen.DELIVERY_SUMMARY_EXPANDED else Screen.DELIVERY_SUMMARY_COLLAPSED,
            isExpanded = parsedPay != null,
            totalPay = headlineTotal,
            parsedPay = parsedPay,
            expandButton = expandButton,
            sessionEarnings = sessionEarnings
        )
    }
}
