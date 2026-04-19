package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.offer.OfferBadge
import cloud.trotter.dashbuddy.domain.model.offer.ParsedOffer
import cloud.trotter.dashbuddy.domain.model.order.OrderBadge
import cloud.trotter.dashbuddy.domain.model.order.OrderType
import cloud.trotter.dashbuddy.domain.model.order.ParsedOrder
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import javax.inject.Inject

class OfferParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.OFFER_POPUP

    override fun parse(node: UiNode): ScreenInfo {
        // --- Parse Header (Pay, Distance, Time) ---
        var payAmount: Double? = null
        var distanceMiles: Double? = null
        var timeText: String? = null

        val textFields = node.findNodes { it.viewIdResourceName?.endsWith("text_field") == true }
        val headerNodes = textFields.ifEmpty { node.findNodes { !it.text.isNullOrEmpty() } }

        for (headerNode in headerNodes) {
            val txt = headerNode.text ?: continue
            when {
                txt.contains("$") && payAmount == null ->
                    payAmount = UtilityFunctions.parseCurrency(txt)
                (txt.contains("mi", true) || txt.contains("ft", true)) && distanceMiles == null &&
                        txt.any { it.isDigit() } ->
                    distanceMiles = UtilityFunctions.parseDistance(txt)
                txt.contains("Deliver by", true) && timeText == null ->
                    timeText = txt.replace("Deliver by ", "", ignoreCase = true).trim()
            }
        }

        val dueByTimeMillis = timeText?.let { UtilityFunctions.parseTimeTextToMillis(it) }

        if (payAmount == null) {
            Timber.w("OfferParser: no pay amount found, falling back to Simple.")
            return ScreenInfo.Simple(targetScreen)
        }

        // --- Parse Orders ---
        val orders = mutableListOf<ParsedOrder>()
        val displayNodes = node.findNodes { it.viewIdResourceName?.endsWith("display_name") == true }

        for (displayNode in displayNodes) {
            val primaryText = displayNode.text ?: continue
            if (primaryText.equals("Customer dropoff", true) ||
                primaryText.equals("Business handoff", true)
            ) continue

            val parent = displayNode.parent
            val scopeNode = parent?.parent ?: parent ?: displayNode

            val typeText = scopeNode.findNode {
                it.viewIdResourceName?.endsWith("work_unit_type") == true
            }?.text ?: ""
            val orderType = if (typeText.contains("Shop", true)) OrderType.SHOP_FOR_ITEMS else OrderType.PICKUP

            val detailsText = scopeNode.findNode {
                it.viewIdResourceName?.endsWith("display_name_secondary") == true
            }?.text ?: ""

            val countVal = UtilityFunctions.parseItemCount(detailsText) ?: 1
            val isMultiOrder = detailsText.contains("order", true)

            val orderBadges = mutableSetOf<OrderBadge>()
            if (scopeNode.findNode {
                    it.viewIdResourceName?.endsWith("tag") == true &&
                            it.text?.contains("Red Card", true) == true
                } != null) orderBadges.add(OrderBadge.RED_CARD)
            if (scopeNode.findNode { it.text?.contains("Large Order", true) == true } != null)
                orderBadges.add(OrderBadge.LARGE_ORDER)
            if (scopeNode.findNode { it.text?.contains("alcohol", true) == true } != null)
                orderBadges.add(OrderBadge.ALCOHOL)

            if (isMultiOrder) {
                repeat(countVal) { idx ->
                    orders.add(ParsedOrder(idx, orderType, primaryText, 1, false, orderBadges))
                }
            } else {
                orders.add(ParsedOrder(0, orderType, primaryText, countVal, false, orderBadges))
            }
        }

        if (orders.isEmpty()) {
            orders.add(ParsedOrder(0, OrderType.PICKUP, "Unknown Store", 1, false, mutableSetOf()))
        }

        val rawHashString = "$payAmount|$distanceMiles|$timeText|${orders.joinToString { it.storeName }}"
        val hash = UtilityFunctions.generateSha256(rawHashString)

        val parsedOffer = ParsedOffer(
            offerHash = hash,
            payAmount = payAmount,
            distanceMiles = distanceMiles,
            itemCount = orders.sumOf { it.itemCount },
            dueByTimeText = timeText,
            dueByTimeMillis = dueByTimeMillis,
            badges = OfferBadge.findAllBadgesInScreen(node.allText),
            orders = orders,
            rawExtractedTexts = "ID-Parsed"
        )
        Timber.i("OfferParser: $parsedOffer")
        return ScreenInfo.Offer(targetScreen, parsedOffer)
    }
}
