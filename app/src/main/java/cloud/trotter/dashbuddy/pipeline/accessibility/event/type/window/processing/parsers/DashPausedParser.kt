package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.ParsedDuration
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import javax.inject.Inject

class DashPausedParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.DASH_PAUSED

    override fun parse(node: UiNode): ScreenInfo {
        val timeString = node.findNode {
            it.viewIdResourceName?.endsWith("progress_number") == true
        }?.text ?: "35:00"

        return ScreenInfo.DashPaused(
            screen = targetScreen,
            remaining = ParsedDuration(timeString, parseTimeString(timeString))
        )
    }

    private fun parseTimeString(time: String): Long {
        return try {
            val parts = time.split(":")
            if (parts.size == 2) {
                val minutes = parts[0].toLong()
                val seconds = parts[1].toLong()
                (minutes * 60 * 1000) + (seconds * 1000)
            } else {
                35 * 60 * 1000L
            }
        } catch (_: Exception) {
            35 * 60 * 1000L
        }
    }
}
