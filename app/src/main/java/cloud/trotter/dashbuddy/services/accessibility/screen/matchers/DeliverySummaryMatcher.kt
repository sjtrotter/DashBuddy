package cloud.trotter.dashbuddy.services.accessibility.screen.matchers

import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.data.pay.ParsedPayItem
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.util.UtilityFunctions
import cloud.trotter.dashbuddy.log.Logger as Log

class DeliverySummaryMatcher : ScreenMatcher {

    // We default to the Expanded screen as the target,
    // but we might return COLLAPSED based on logic.
    override val targetScreen = Screen.DELIVERY_SUMMARY_EXPANDED
    override val priority = 10

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // --- 1. ANCHORS (Is this the Summary Screen?) ---

        // Anchor A: "This offer" text
        val hasOfferTitle = root.findNode {
            it.text.equals("This offer", ignoreCase = true)
        } != null

        // Anchor B: "Continue dashing" button
        val hasContinueBtn = root.findNode {
            it.text.equals("Continue dashing", ignoreCase = true)
        } != null

        if (!hasOfferTitle || !hasContinueBtn) {
            return null
        }

        // --- 2. STATE CHECK (Collapsed vs Expanded) ---

        // We look for the section headers that appear only when expanded
        val hasDoorDashPay =
            root.findNode { it.text.equals("DoorDash pay", ignoreCase = true) } != null
        val hasTips = root.findNode { it.text.equals("Customer tips", ignoreCase = true) } != null

        // If headers are missing, we are Collapsed.
        if (!hasDoorDashPay || !hasTips) {
            return ScreenInfo.Simple(Screen.DELIVERY_SUMMARY_COLLAPSED)
        }

        // --- 3. PARSING (Expanded Mode) ---
        // Since we are expanded, we can now extract the lines using their stable IDs.
        // Log ID: pay_line_item_title (e.g., "Base pay")
        // Log ID: pay_line_item_value (e.g., "$2.00")

        val appPayItems = mutableListOf<ParsedPayItem>()
        val tipItems = mutableListOf<ParsedPayItem>()

        // We need to know which section we are in.
        // Strategy: Iterate through the pay lines and assume order, or find the headers and look at siblings.
        // Given the logs, the structure is linear. We can treat all items as "Pay Items" for now,
        // or attempt to categorize them if we find the headers in the node list.

        // Simplified Logic:
        // 1. Find all nodes with ID 'pay_line_item_title'
        // 2. Their sibling/partner is 'pay_line_item_value'
        // 3. Categorize based on text (Tips vs Pay)

        // Let's scan the whole tree for pay lines
        val payLines =
            root.findNodes { it.viewIdResourceName?.endsWith("pay_line_item_title") == true }

        for (titleNode in payLines) {
            val label = titleNode.text ?: continue

            // Find the value associated with this title.
            // In your logs, they are siblings in a LinearLayout.
            val parent = titleNode.parent
            val valueNode =
                parent?.children?.find { it.viewIdResourceName?.endsWith("pay_line_item_value") == true }
            val amountStr = valueNode?.text ?: ""

            val amount = UtilityFunctions.parseCurrency(amountStr) ?: 0.0

            // Categorization Logic
            if (label.contains("Base pay", true) || label.contains(
                    "Peak pay",
                    true
                ) || label.contains("Bonus", true)
            ) {
                appPayItems.add(ParsedPayItem(label, amount))
            } else {
                // Usually store names (e.g. "Jet's Pizza") or "Customer tip" fall here
                tipItems.add(ParsedPayItem(label, amount))
            }
        }

        val expandButton = root.findNode {
            it.viewIdResourceName?.endsWith("expandable_view") == true
        } ?: root.findNode {
            it.text?.startsWith("This offer") == true
        }

        return ScreenInfo.DeliveryCompleted(
            screen = Screen.DELIVERY_SUMMARY_EXPANDED,
            parsedPay = ParsedPay(appPayItems, tipItems),
            expandButton = expandButton,
        )
    }
}