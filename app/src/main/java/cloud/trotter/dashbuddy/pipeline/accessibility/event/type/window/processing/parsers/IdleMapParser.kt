package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.domain.state.SessionType
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import timber.log.Timber
import javax.inject.Inject

class IdleMapParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.MAIN_MAP_IDLE

    override fun parse(node: UiNode): ParsedFields {
        var sessionType: SessionType? = null
        if (node.findNode { it.contentDescription == "Time mode off" } != null) {
            sessionType = SessionType.PerOffer
        } else if (node.findNode { it.contentDescription == "Time mode on" } != null) {
            sessionType = SessionType.ByTime
        }
        Timber.v("Parsed SessionType: $sessionType")
        return ParsedFields.IdleFields(zoneName = null, sessionType = sessionType)
    }
}
