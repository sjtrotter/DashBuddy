package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import javax.inject.Inject

class PickupShoppingParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP

    // Matches "To shop (N)" tab label — live count of remaining items to scan.
    private val toShopRegex = Regex("To shop \\((\\d+)\\)", RegexOption.IGNORE_CASE)

    override fun parse(node: UiNode): ScreenInfo {
        // "To shop (N)" tab in the tab_layout shows remaining item count in real time.
        val itemCount = node.findNode {
            it.viewIdResourceName?.endsWith("tab_layout") == true ||
                it.text?.let { t -> toShopRegex.containsMatchIn(t) } == true
        }?.let { tabNode ->
            // May be the tab_layout container — look for the "To shop (N)" text among its descendants.
            val matchNode = if (toShopRegex.containsMatchIn(tabNode.text.orEmpty())) tabNode
            else tabNode.findNode { toShopRegex.containsMatchIn(it.text.orEmpty()) }
            matchNode?.text?.let { t -> toShopRegex.find(t)?.groupValues?.get(1)?.toIntOrNull() }
        }

        // Store name is not available on this screen; handler uses sticky logic from prior state.
        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = null,
            itemCount = itemCount,
            status = PickupStatus.SHOPPING
        )
    }
}
