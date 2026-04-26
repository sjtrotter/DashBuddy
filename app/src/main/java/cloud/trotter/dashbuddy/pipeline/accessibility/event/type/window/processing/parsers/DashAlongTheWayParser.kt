package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import javax.inject.Inject

class DashAlongTheWayParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.ON_DASH_ALONG_THE_WAY

    override fun parse(node: UiNode): ScreenInfo {
        // "Spot saved until 15:57 (43 mins)" — present when a spot-save timer is active.
        val spotSaveText = node.findNode {
            it.viewIdResourceName?.endsWith("bottom_view_info_title") == true
        }?.text

        // Parse the deadline time from "Spot saved until HH:mm (N mins)" → epoch millis.
        val spotSaveDeadline = spotSaveText?.let { UtilityFunctions.parseDeadlineMillis(it) }

        return ScreenInfo.WaitingForOffer(
            screen = targetScreen,
            dashPay = null,
            waitTimeEstimate = null,
            isHeadingBackToZone = false,
            spotSaveDeadline = spotSaveDeadline
        )
    }
}
