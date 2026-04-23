package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import javax.inject.Inject

class DeliverySummaryMatcher @Inject constructor() : ScreenMatcher {

    // Parser will set the authoritative screen (EXPANDED vs COLLAPSED); this is the lookup key.
    override val targetScreen: Screen = Screen.DELIVERY_SUMMARY_EXPANDED
    override val priority: Int = 20

    override fun matches(node: UiNode): Screen? {
        // The timeline screen also contains "This offer" in its earnings section.
        // Guard against that false positive — the real delivery summary never has "pause orders".
        val hasPauseOrders = node.hasNode {
            (it.text ?: "").contains("pause orders", ignoreCase = true)
        }
        if (hasPauseOrders) return null

        val hasContext = node.hasNode {
            val text = it.text ?: ""
            text.contains("This offer", ignoreCase = false) ||
                    text.contains("Delivery Complete", ignoreCase = true)
        }
        return if (hasContext) targetScreen else null
    }
}