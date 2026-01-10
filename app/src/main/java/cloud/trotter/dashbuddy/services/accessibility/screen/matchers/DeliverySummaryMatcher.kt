package cloud.trotter.dashbuddy.services.accessibility.screen.matchers

import cloud.trotter.dashbuddy.data.pay.ParsedPay
import cloud.trotter.dashbuddy.data.pay.ParsedPayItem
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenMatcher
import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.util.UtilityFunctions
import cloud.trotter.dashbuddy.log.Logger as Log

class DeliverySummaryMatcher : ScreenMatcher {

    private val tag = "DeliverySummaryMatcher"

    // We default to the Expanded screen as the target,
    // but we might return COLLAPSED based on logic.
    override val targetScreen = Screen.DELIVERY_SUMMARY_EXPANDED
    override val priority = 10

    override fun matches(context: StateContext): ScreenInfo? {
        val root = context.rootUiNode ?: return null

        // --- 1. ANCHORS (Is this the Summary Screen?) ---
        val hasOfferTitle = root.findNode {
            it.text.equals("This offer", ignoreCase = true)
        } != null

        val hasContinueBtn = root.findNode {
            it.text.equals("Continue dashing", ignoreCase = true)
        } != null

        if (!hasOfferTitle || !hasContinueBtn) {
            return null
        }

        // --- 2. STATE CHECK (Collapsed vs Expanded) ---

        // We look for the section headers that appear only when expanded
        val doorDashPayHeader = root.findNode { it.text.equals("DoorDash pay", ignoreCase = true) }
        val customerTipsHeader =
            root.findNode { it.text.equals("Customer tips", ignoreCase = true) }

        val isExpanded = doorDashPayHeader != null && customerTipsHeader != null

        // If headers are missing, we are Collapsed.
        if (!isExpanded) {
            Log.i(
                tag,
                "Headers missing. Attempting to find Expand Button relative to 'This offer'..."
            )

            // 1. Find the "This offer" text anchor
            val offerTitleNode = root.findNode {
                it.text.equals("This offer", ignoreCase = true)
            }

            var expandButton: UiNode? = null

            if (offerTitleNode != null) {
                // 2. Walk up the tree to find the container that holds the button.
                // We do this to ensure we don't accidentally click the "This dash so far" expandable_view
                var container = offerTitleNode.parent
                var attempts = 0

                // Safety loop: Walk up max 3 levels to find the sibling button
                while (container != null && expandButton == null && attempts < 3) {
                    expandButton = container.children.find {
                        it.viewIdResourceName?.endsWith("expandable_view") == true
                    }

                    if (expandButton == null) {
                        container = container.parent
                        attempts++
                    }
                }
            }

            // Fallback: If logic failed, try clicking the text itself
            if (expandButton == null) {
                Log.w(
                    tag,
                    "Could not find specific 'expandable_view' sibling. Falling back to clicking the Title Text."
                )
                expandButton = offerTitleNode
            }

            return if (expandButton != null) {
                Log.i(tag, "Returning COLLAPSED state with action to click: $expandButton")
                ScreenInfo.DeliverySummaryCollapsed(
                    screen = Screen.DELIVERY_SUMMARY_COLLAPSED,
                    expandButton = expandButton
                )
            } else {
                Log.e(tag, "Returning COLLAPSED state but NO CLICK TARGET found.")
                ScreenInfo.Simple(Screen.DELIVERY_SUMMARY_COLLAPSED)
            }
        }

// --- 3. ROBUST SECTION-BASED PARSING ---
        Log.i(tag, "Expanded state detected. Parsing sections...")

        val appPayItems = mutableListOf<ParsedPayItem>()
        val tipItems = mutableListOf<ParsedPayItem>()

        // The structure is typically:
        // RecyclerView (expandable_items)
        //   -> ViewGroup (Header: "DoorDash pay")
        //   -> ViewGroup (Item: "Base pay", "$3.00")
        //   -> ViewGroup (Item: "Arbitrary Pay", "$3.00")
        //   -> ViewGroup (Header: "Customer tips")
        //   -> ViewGroup (Item: "Walgreens", "$5.00")

        // Strategy: Iterate through the children of the parent container (the RecyclerView).
        // Use the headers as "Switch" signals.

        // Find the common parent of the headers
        val listContainer = doorDashPayHeader.parent?.parent
        // Note: In your log: header -> parent(ViewGroup) -> parent(RecyclerView id=expandable_items)

        if (listContainer != null && listContainer.children.isNotEmpty()) {
            var currentSection = "NONE" // NONE, DD_PAY, TIPS

            for (childContainer in listContainer.children) {
                // Check if this child container holds a HEADER
                val headerText = childContainer.findNode {
                    it.text.equals("DoorDash pay", true) || it.text.equals("Customer tips", true)
                }?.text

                if (headerText != null) {
                    if (headerText.equals("DoorDash pay", true)) {
                        currentSection = "DD_PAY"
                    } else if (headerText.equals("Customer tips", true)) {
                        currentSection = "TIPS"
                    }
                    continue // Skip parsing the header row itself
                }

                // If it's not a header, it's a pay item row. Parse it!
                // Look for name/value pairs inside this row
                val nameNode = childContainer.findNode {
                    it.viewIdResourceName?.endsWith("name") == true ||
                            it.viewIdResourceName?.endsWith("pay_line_item_title") == true
                }
                val valueNode = childContainer.findNode {
                    it.viewIdResourceName?.endsWith("value") == true ||
                            it.viewIdResourceName?.endsWith("pay_line_item_value") == true
                }

                if (nameNode != null && valueNode != null) {
                    val label = nameNode.text ?: "Unknown"
                    val amountStr = valueNode.text ?: ""
                    val amount = UtilityFunctions.parseCurrency(amountStr) ?: 0.0

                    Log.v(tag, "Parsed Item in section $currentSection: '$label' -> $amount")

                    if (currentSection == "DD_PAY") {
                        appPayItems.add(ParsedPayItem(label, amount))
                    } else if (currentSection == "TIPS") {
                        tipItems.add(ParsedPayItem(label, amount))
                    }
                }
            }
        } else {
            Log.e(tag, "Could not find list container for pay items.")
        }

        val result = ParsedPay(appPayItems, tipItems)
        Log.i(
            tag,
            "Parsing Complete. Total DD: ${result.totalBasePay}, Total Tip: ${result.totalTip}"
        )

        return ScreenInfo.DeliveryCompleted(
            screen = Screen.DELIVERY_SUMMARY_EXPANDED,
            parsedPay = result,
        )
    }
}