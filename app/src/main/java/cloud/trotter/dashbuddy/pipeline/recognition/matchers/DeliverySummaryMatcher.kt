package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.data.pay.PayParser
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import kotlin.math.abs
import cloud.trotter.dashbuddy.log.Logger as Log

class DeliverySummaryMatcher : ScreenMatcher {
    override val targetScreen: Screen = Screen.DELIVERY_SUMMARY_EXPANDED
    override val priority: Int = 20

    private val tag = "DeliverySummaryMatcher"

    override fun matches(node: UiNode): ScreenInfo? {

        // 1. Context Check
        val hasContext = node.hasNode {
            val text = it.text ?: ""
            text.contains("This offer", ignoreCase = true) ||
                    text.contains("Delivery Complete", ignoreCase = true)
        }

        if (!hasContext) return null

        Log.d(tag, "Context found. Proceeding with analysis.")

        // 2. Find the Correct Container
        val earningsContainer = node.findNodes { it.hasId("earnings_container") }
            .find { container ->
                container.hasNode { it.text?.contains("This offer", ignoreCase = true) == true }
            }

        Log.d(tag, "Target Earnings Container found: ${earningsContainer != null}")

        // 3. Extract Headline Total (Validation)
        val finalValueNode = earningsContainer?.findDescendantById("final_value")
            ?: node.findDescendantById("final_value")

        val headlineTotal = finalValueNode?.text?.replace("$", "")?.toDoubleOrNull()

        Log.d(tag, "Headline Total: $headlineTotal (Node text: ${finalValueNode?.text})")

        // 4. Parse Breakdown
        val parsedScope = earningsContainer ?: node
        val parsedPay = PayParser.parsePayFromTree(parsedScope)
        val breakdownTotal = parsedPay.total

        Log.d(
            tag,
            "Parsed Breakdown Total: $breakdownTotal (Components: ${parsedPay.appPayComponents.size} app, ${parsedPay.customerTips.size} tips)"
        )

        // 5. Validation Logic
        // We now enforce STRICT equality (within 2 cents).
        // It must NOT be significantly lower (loading) OR significantly higher (bad parse).
        val isValid = if (headlineTotal != null) {
            val diff = abs(breakdownTotal - headlineTotal)
            val isCloseEnough = diff <= 0.02

            if (!isCloseEnough) {
                Log.w(
                    tag,
                    "Validation FAILED: Mismatch! Breakdown ($breakdownTotal) vs Headline ($headlineTotal). Diff: $diff"
                )
            }
            isCloseEnough
        } else {
            val hasData = parsedPay.appPayComponents.isNotEmpty()
            if (!hasData) Log.w(
                tag,
                "Validation FAILED: No headline and no parsed app pay components."
            )
            hasData
        }

        if (isValid) {
            Log.i(tag, "Match Success: DELIVERY_SUMMARY_EXPANDED")
            return ScreenInfo.DeliveryCompleted(
                screen = Screen.DELIVERY_SUMMARY_EXPANDED,
                parsedPay = parsedPay
            )
        } else {
            // 6. Fallback: Collapsed / Loading
            Log.d(tag, "Data invalid/incomplete. Attempting fallback to COLLAPSED state.")

            val expandButton = earningsContainer?.findDescendantById("expandable_view")
                ?: earningsContainer?.findDescendantById("expandable_layout")
                ?: node.findNodes { it.hasId("expandable_view") }.lastOrNull()

            if (expandButton != null) {
                Log.i(tag, "Match Success: DELIVERY_SUMMARY_COLLAPSED (Found expand button)")
                return ScreenInfo.DeliverySummaryCollapsed(
                    screen = Screen.DELIVERY_SUMMARY_COLLAPSED,
                    expandButton = expandButton
                )
            } else {
                Log.w(tag, "Fallback Failed: Could not find expand button.")
            }
        }

        return null
    }
}