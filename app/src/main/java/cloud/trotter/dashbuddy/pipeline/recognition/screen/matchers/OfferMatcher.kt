package cloud.trotter.dashbuddy.pipeline.recognition.screen.matchers

import android.os.Build
import androidx.annotation.RequiresApi
import cloud.trotter.dashbuddy.data.offer.OfferBadge
import cloud.trotter.dashbuddy.data.offer.ParsedOffer
import cloud.trotter.dashbuddy.data.order.OrderBadge
import cloud.trotter.dashbuddy.data.order.OrderType
import cloud.trotter.dashbuddy.data.order.ParsedOrder
import cloud.trotter.dashbuddy.pipeline.model.UiNode
import cloud.trotter.dashbuddy.pipeline.recognition.screen.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.screen.ScreenMatcher
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import javax.inject.Inject

//import cloud.trotter.dashbuddy.log.Logger as Log

class OfferMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.OFFER_POPUP
    override val priority = 20

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    override fun matches(node: UiNode): ScreenInfo? {
        // --- 1. FAIL FAST CHECKS ---
        if (node.findNode { it.viewIdResourceName?.endsWith("progress_bar") == true } != null) return null
        if (node.findNode { it.text?.contains("sure you want to decline", true) == true } != null) {
            return ScreenInfo.Simple(Screen.OFFER_POPUP_CONFIRM_DECLINE)
        }

        // Must have Decline AND (Accept OR Add to route)
        val hasDecline = node.findNode { it.text.equals("Decline", ignoreCase = true) } != null
        val hasAccept = node.findNode {
            val txt = it.text
            txt.equals("Accept", true) || txt.equals("Add to route", true)
        } != null

        if (!hasDecline || !hasAccept) return null

        // --- 2. PARSE HEADER (Pay, Distance, Time) ---
        var payAmount: Double? = null
        var distanceMiles: Double? = null
        var timeText: String? = null

        // Scan text_field IDs (Standard UI)
        val textFields = node.findNodes { it.viewIdResourceName?.endsWith("text_field") == true }

        // Fallback: Scan all text if IDs missing (Legacy support)
        val headerNodes = textFields.ifEmpty { node.findNodes { !it.text.isNullOrEmpty() } }

        for (node in headerNodes) {
            val txt = node.text ?: continue

            if (txt.contains("$")) {
                if (payAmount == null) payAmount = UtilityFunctions.parseCurrency(txt)
            } else if ((txt.contains("mi", true) || txt.contains(
                    "ft",
                    true
                )) && distanceMiles == null
            ) {
                if (txt.any { it.isDigit() }) distanceMiles = UtilityFunctions.parseDistance(txt)
            } else if (txt.contains("Deliver by", true) && timeText == null) {
                timeText = txt.replace("Deliver by ", "", ignoreCase = true).trim()
            }
        }

        val dueByTimeMillis = timeText?.let {
            UtilityFunctions.parseTimeTextToMillis(it)
        }

        if (payAmount == null) return null // Critical failure

        // --- 3. PARSE ORDERS (The Spaghetti Replacement) ---
        val orders = mutableListOf<ParsedOrder>()

        // Find all "display_name" nodes. These are strictly Stores or "Customer dropoff".
        val displayNodes =
            node.findNodes { it.viewIdResourceName?.endsWith("display_name") == true }

        for (node in displayNodes) {
            val primaryText = node.text ?: continue

            // Skip Dropoffs
            if (primaryText.equals("Customer dropoff", true) ||
                primaryText.equals("Business handoff", true)
            ) continue

            // We found a Store! Now look at its surroundings (siblings/cousins).
            // The structure is usually:
            // [WorkType] -> [Container [StoreName] [Details]] -> [Tags]

            // 1. Determine Order Type (Sibling "work_unit_type")
            // We search up to the common parent or nearby nodes.
            // Since `UiNode` implies a tree, we check the parent's other children.
            val parent = node.parent
            val grandParent = parent?.parent

            // Search scope: The container holding this store block
            val scopeNode = grandParent ?: parent ?: node

            val typeNode =
                scopeNode.findNode { it.viewIdResourceName?.endsWith("work_unit_type") == true }
            val typeText = typeNode?.text ?: ""
            val orderType =
                if (typeText.contains("Shop", true)) OrderType.SHOP_FOR_ITEMS else OrderType.PICKUP

            // 2. Determine Item/Order Count (Sibling "display_name_secondary")
            val detailsNode =
                scopeNode.findNode { it.viewIdResourceName?.endsWith("display_name_secondary") == true }
            val detailsText = detailsNode?.text ?: "" // e.g., "(2 items)", "(2 orders)"

            val countVal = UtilityFunctions.parseItemCount(detailsText) ?: 1

            // 3. Detect "Multi-Order" vs "Multi-Item"
            // If text says "orders", we create multiple orders for this store.
            val isMultiOrder = detailsText.contains("order", true)

            // 4. Detect Badges (Red Card, Catering, etc.)
            // We look for specific text or IDs within this store's scope
            val orderBadges = mutableSetOf<OrderBadge>()

            // Check for "tag" ID (e.g. "Red Card")
            if (scopeNode.findNode {
                    it.viewIdResourceName?.endsWith("tag") == true && it.text?.contains(
                        "Red Card",
                        true
                    ) == true
                } != null) {
                orderBadges.add(OrderBadge.RED_CARD)
            }
            // Check for text-based badges
            if (scopeNode.findNode { it.text?.contains("Large Order", true) == true } != null) {
                orderBadges.add(OrderBadge.LARGE_ORDER)
            }
            if (scopeNode.findNode { it.text?.contains("alcohol", true) == true } != null) {
                orderBadges.add(OrderBadge.ALCOHOL)
            }

            // 5. Create the ParsedOrder(s)
            if (isMultiOrder) {
                // "(2 orders)" -> Create 2 separate orders for this store
                repeat(countVal) {
                    orders.add(
                        ParsedOrder(
                            storeName = primaryText,
                            itemCount = 1, // Unknown items per order, default to 1
                            orderType = orderType,
                            badges = orderBadges,
                            orderIndex = 0,
                            isItemCountEstimated = false,
                        )
                    )
                }
            } else {
                // "(5 items)" -> Create 1 order
                orders.add(
                    ParsedOrder(
                        storeName = primaryText,
                        itemCount = countVal,
                        orderType = orderType,
                        badges = orderBadges,
                        orderIndex = 0,
                        isItemCountEstimated = false,
                    )
                )
            }
        }

        // Fallback: If no orders parsed (e.g. old UI), create dummy
        if (orders.isEmpty()) {
            orders.add(
                ParsedOrder(
                    storeName = "Unknown Store",
                    itemCount = 1,
                    orderType = OrderType.PICKUP,
                    badges = mutableSetOf(),
                    orderIndex = 0,
                    isItemCountEstimated = false
                )
            )
        }

        // --- 4. HASHING & RETURN ---
        val rawHashString =
            "$payAmount|$distanceMiles|$timeText|${orders.joinToString { it.storeName }}"
        val hash = UtilityFunctions.generateSha256(rawHashString)

        val parsedOffer = ParsedOffer(
            offerHash = hash,
            payAmount = payAmount,
            distanceMiles = distanceMiles,
            itemCount = orders.sumOf { it.itemCount },
            dueByTimeText = timeText,
            dueByTimeMillis = dueByTimeMillis,
            badges = OfferBadge.findAllBadgesInScreen(node.allText), // Global badges
            orders = orders,
            rawExtractedTexts = "ID-Parsed"
        )
        Timber.i("Matched offer: $parsedOffer")
//        DashBuddyApplication.sendBubbleMessage("New parsed offer: $parsedOffer")
//        return null
        return ScreenInfo.Offer(Screen.OFFER_POPUP, parsedOffer)
    }
}