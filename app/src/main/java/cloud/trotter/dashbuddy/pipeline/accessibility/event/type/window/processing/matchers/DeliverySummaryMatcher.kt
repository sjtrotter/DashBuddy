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
    private val payParser: PayParser // <--- Injected Dependency
) : ScreenMatcher {

    override val targetScreen: Screen = Screen.DELIVERY_SUMMARY_EXPANDED
    override val priority: Int = 20

    override fun matches(node: UiNode): ScreenInfo? {

        // 1. Context Check
        val hasContext = node.hasNode {
            val text = it.text ?: ""
            text.contains("This offer", ignoreCase = true) ||
                    text.contains("Delivery Complete", ignoreCase = true)
        }

        if (!hasContext) return null

        // 2. Find the Correct Container
        val earningsContainer = node.findNodes { it.matchesId("earnings_container") }
            .find { container ->
                container.hasNode { it.text?.contains("This offer", ignoreCase = true) == true }
            }

        // Define the scope for searching (prefer container, fallback to root)
        val searchScope = earningsContainer ?: node

        // 3. Fast Fail: Check for "Anchor" Nodes
        // If these text nodes are missing, the screen is definitely NOT expanded (or not loaded).
        // We skip parsing entirely and check for the button immediately.
        val hasDoorDashPay =
            searchScope.hasNode { it.text?.contains("DoorDash pay", ignoreCase = true) == true }
        val hasTips =
            searchScope.hasNode { it.text?.contains("Customer tips", ignoreCase = true) == true }

        if (!hasDoorDashPay || !hasTips) {
            Timber.d(
                "Missing expanded anchors (DD Pay: $hasDoorDashPay, Tips: $hasTips). Checking for Collapsed state."
            )
            return attemptFallbackToCollapsed(node, earningsContainer)
        }

        Timber.d("Anchors found. Proceeding to Parse.")

        // 4. Extract Headline Total (Validation)
        val finalValueNode = earningsContainer?.findDescendantById("final_value")
            ?: node.findDescendantById("final_value")

        val headlineTotal = finalValueNode?.text?.replace("$", "")?.toDoubleOrNull()

        // 5. Parse Breakdown
        val parsedPay = payParser.parsePayFromTree(searchScope)
        val breakdownTotal = parsedPay.total

        // 6. Validation Timber.c
        val isValid = if (headlineTotal != null) {
            val diff = abs(breakdownTotal - headlineTotal)
            val isCloseEnough = diff <= 0.02

            if (!isCloseEnough) {
                Timber.w(
                    "Validation FAILED: Mismatch! Breakdown ($breakdownTotal) vs Headline ($headlineTotal). Diff: $diff"
                )
            }
            isCloseEnough
        } else {
            val hasData = parsedPay.appPayComponents.isNotEmpty()
            if (!hasData) Timber.w(
                "Validation FAILED: No headline and no parsed app pay components."
            )
            hasData
        }

        if (isValid) {
            Timber.i("Match Success: DELIVERY_SUMMARY_EXPANDED")
            return ScreenInfo.DeliveryCompleted(
                screen = Screen.DELIVERY_SUMMARY_EXPANDED,
                parsedPay = parsedPay
            )
        } else {
            // If validation failed (e.g. mismatch), we can still try to fall back
            // just in case it was a bad read of a collapsed screen.
            Timber.w("Data invalid despite anchors. Attempting fallback.")
            return attemptFallbackToCollapsed(node, earningsContainer)
        }
    }

    /**
     * Helper to check if we are in the Collapsed state by locating the expand button.
     */
    private fun attemptFallbackToCollapsed(root: UiNode, container: UiNode?): ScreenInfo? {
        val expandButton = findExpandButton(root, container)

        if (expandButton != null) {
            Timber.i("Match Success: DELIVERY_SUMMARY_COLLAPSED (Found expand button)")
            return ScreenInfo.DeliverySummaryCollapsed(
                screen = Screen.DELIVERY_SUMMARY_COLLAPSED,
                expandButton = expandButton
            )
        }

        Timber.w("Fallback Failed: Could not find expand button.")
        return null
    }

    /**
     * Encapsulated logic for finding the expand/collapse button
     */
    private fun findExpandButton(root: UiNode, container: UiNode?): UiNode? {
        return container?.findDescendantById("expandable_view")
            ?: container?.findDescendantById("expandable_layout")
            ?: root.findNodes { it.matchesId("expandable_view") }.lastOrNull()
    }
}