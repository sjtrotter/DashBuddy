package cloud.trotter.dashbuddy.state.parsers

import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.log.Logger as Log

object IdleMapParser {

    // This logic is moved from the DasherIdleOffline handler.
    // It now takes the full list of screen texts to be more robust.
    fun parse(screenTexts: List<String>): Pair<String?, DashType?> {
        var zoneName: String?
        var dashType: DashType? = null

        // Find Earning Type
        for (text in screenTexts.withIndex()) {
            if (text.value.matches(Regex("^\\$\\d{1,2}\\.\\d{2}/active hr \\+ tips$"))) {
                dashType = DashType.BY_TIME
            } else if (text.value.contains("Pay per offer + Customer tips") ||
                text.value.contains("Dash Along the Way")
            ) {
                dashType = DashType.PER_OFFER
            }
        }

        var zonePreIndex = screenTexts.indexOfFirst { it.contains("Promos", ignoreCase = true) }
        if (zonePreIndex == -1) {
            zonePreIndex = screenTexts.indexOfFirst { it.contains("Help", ignoreCase = true) }
        }

        zoneName = if (zonePreIndex != -1) {
            screenTexts[zonePreIndex + 1]
        } else {
            null
        }

        Log.d("IdleMapParser", "Parsed Zone: $zoneName, Type: $dashType")
        return Pair(zoneName, dashType)
    }
}