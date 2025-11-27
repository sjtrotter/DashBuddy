package cloud.trotter.dashbuddy.services.accessibility.screen.parsers

import cloud.trotter.dashbuddy.data.dash.DashType
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.log.Logger as Log

object IdleMapNodeParser {

    fun parse(rootNode: UiNode?): Pair<String?, DashType?> {
        if (rootNode == null) {
            return Pair(null, null)
        }
        var zoneName: String? = null
        var dashType: DashType? = null

        // 1. Find the Dash Type using contentDescription, which is more stable.
        if (rootNode.findNode { it.contentDescription == "Time mode off" } != null) {
            dashType = DashType.PER_OFFER
        } else if (rootNode.findNode { it.contentDescription == "Time mode on" } != null) {
            dashType = DashType.BY_TIME
        }

        // 2. Find the zone name by locating the ScrollView first.
        val scrollView = rootNode.findNode { it.className == "android.widget.ScrollView" }

        // 3. The zone is the first TextView child within that ScrollView.
        // This is robust because it doesn't care about the other arbitrary text nodes.
        zoneName = scrollView?.children?.firstOrNull {
            it.className == "android.widget.TextView" && !it.text.isNullOrEmpty()
        }?.text ?: "Unknown Zone"

        Log.d("IdleMapNodeParser", "Parsed Zone: $zoneName, Type: $dashType")
        return Pair(zoneName, dashType)
    }
}