package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import javax.inject.Inject

class DashAlongTheWayParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.ON_DASH_ALONG_THE_WAY

    override fun parse(node: UiNode): ScreenInfo {
        // Pay and wait time are not shown on this overlay; forward navigation is never "heading back".
        return ScreenInfo.WaitingForOffer(
            screen = targetScreen,
            currentDashPay = null,
            waitTimeEstimate = null,
            isHeadingBackToZone = false
        )
    }
}
