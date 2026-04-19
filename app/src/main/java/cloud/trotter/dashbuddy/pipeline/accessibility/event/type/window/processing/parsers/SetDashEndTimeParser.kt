package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import javax.inject.Inject

class SetDashEndTimeParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.SET_DASH_END_TIME

    override fun parse(node: UiNode): ScreenInfo {
        val zoneName = node.findNode {
            it.viewIdResourceName?.endsWith("starting_point_name") == true
        }?.text

        return ScreenInfo.IdleMap(
            screen = targetScreen,
            zoneName = zoneName,
            dashType = null
        )
    }
}
