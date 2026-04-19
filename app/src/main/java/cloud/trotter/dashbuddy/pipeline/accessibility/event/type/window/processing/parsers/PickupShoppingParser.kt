package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.order.PickupStatus
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import javax.inject.Inject

class PickupShoppingParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.PICKUP_DETAILS_POST_ARRIVAL_SHOP

    override fun parse(node: UiNode): ScreenInfo {
        // Store name is not available on this screen; handler uses sticky logic from prior state.
        return ScreenInfo.PickupDetails(
            screen = targetScreen,
            storeName = null,
            status = PickupStatus.SHOPPING
        )
    }
}
