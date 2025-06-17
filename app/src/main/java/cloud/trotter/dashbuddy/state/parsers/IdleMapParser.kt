package cloud.trotter.dashbuddy.state.parsers

import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.log.Logger as Log

object IdleMapParser {

    // This logic is moved from the DasherIdleOffline handler.
    // It now takes the full list of screen texts to be more robust.
    fun parse(screenTexts: List<String>): Pair<String?, DashType?> {
        var zoneName: String? = null
        var dashType: DashType? = null

        // Find Earning Type
        for (text in screenTexts) {
            if (text.matches(Regex("^\\$\\d{1,2}\\.\\d{2}/active hr \\+ tips$"))) {
                dashType = DashType.BY_TIME
            } else if (text.contains("Pay per offer + Customer tips") ||
                text.contains("Dash Along the Way")
            ) {
                dashType = DashType.PER_OFFER
            }
        }

        var zonePreIndex = screenTexts.indexOf("Promos")
        if (zonePreIndex == -1) {
            zonePreIndex = screenTexts.indexOf("Help")
        }
        if (zonePreIndex != -1) {
            zoneName = screenTexts.getOrNull(zonePreIndex + 1)
        }

        Log.d("IdleMapParser", "Parsed Zone: $zoneName, Type: $dashType")
        return Pair(zoneName, dashType)
    }
}