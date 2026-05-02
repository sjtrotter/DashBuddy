package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.ParsedFields
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import javax.inject.Inject

class SensitiveScreenParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.SENSITIVE

    override fun parse(node: UiNode): ParsedFields = ParsedFields.SensitiveFields()
}
