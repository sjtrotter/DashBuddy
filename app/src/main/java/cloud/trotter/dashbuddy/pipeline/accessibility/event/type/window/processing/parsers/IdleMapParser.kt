package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.dash.DashType
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import timber.log.Timber
import javax.inject.Inject

class IdleMapParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.MAIN_MAP_IDLE

    override fun parse(node: UiNode): ScreenInfo {
        var dashType: DashType? = null
        if (node.findNode { it.contentDescription == "Time mode off" } != null) {
            dashType = DashType.PER_OFFER
        } else if (node.findNode { it.contentDescription == "Time mode on" } != null) {
            dashType = DashType.BY_TIME
        }
        Timber.v("Parsed DashType: $dashType")
        return ScreenInfo.IdleMap(screen = targetScreen, zoneName = null, dashType = dashType)
    }
}
