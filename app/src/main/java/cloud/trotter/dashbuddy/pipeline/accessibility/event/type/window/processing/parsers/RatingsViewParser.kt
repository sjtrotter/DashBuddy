package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import timber.log.Timber
import javax.inject.Inject

class RatingsViewParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.RATINGS_VIEW

    override fun parse(node: UiNode): ScreenInfo {
        // Metrics appear as consecutive textView_title / textView_description sibling pairs.
        // Section headers (e.g. "Your ratings", "Overall", "Shopping") also use textView_title
        // but have no adjacent textView_description — they are skipped naturally by this loop.
        val metricNodes = node.findNodes {
            it.viewIdResourceName?.endsWith("textView_title") == true ||
                it.viewIdResourceName?.endsWith("textView_description") == true
        }

        val metrics = mutableMapOf<String, String>()
        var i = 0
        while (i < metricNodes.size - 1) {
            val cur = metricNodes[i]
            val nxt = metricNodes[i + 1]
            if (cur.viewIdResourceName?.endsWith("textView_title") == true &&
                nxt.viewIdResourceName?.endsWith("textView_description") == true
            ) {
                val label = cur.text
                val value = nxt.text
                if (label != null && value != null) metrics[label] = value
                i += 2
            } else {
                i++
            }
        }

        Timber.d("RatingsViewParser: extracted ${metrics.size} metric(s): $metrics")

        return ScreenInfo.Ratings(
            screen = targetScreen,
            acceptanceRate = metrics["Acceptance rate"]?.parsePercent(),
            completionRate = metrics["Completion rate"]?.parsePercent(),
            onTimeRate = metrics["On-time rate"]?.parsePercent(),
            customerRating = metrics["Customer rating"]?.toDoubleOrNull(),
            deliveriesLast30Days = metrics["Deliveries last 30 days"]?.toIntOrNull(),
            lifetimeDeliveries = metrics["Lifetime deliveries"]?.toIntOrNull(),
            originalItemsFoundRate = metrics["Original items found"]?.parsePercent(),
            totalItemsFoundRate = metrics["Total items found"]?.parsePercent(),
            substitutionIssuesRate = metrics["Substitution issues"]?.parsePercent(),
            itemsWithQualityIssuesRate = metrics["Items with quality issues"]?.parsePercent(),
            itemsWrongOrMissingRate = metrics["Items that were wrong or missing"]?.parsePercent(),
            lifetimeShoppingOrders = metrics["Lifetime shopping orders"]?.toIntOrNull(),
        )
    }

    private fun String.parsePercent(): Double? = removeSuffix("%").trim().toDoubleOrNull()
}
