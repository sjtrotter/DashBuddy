package cloud.trotter.dashbuddy.state.parsers

import cloud.trotter.dashbuddy.state.ClickInfo
import cloud.trotter.dashbuddy.log.Logger as Log

object ClickParser {
    private const val TAG = "ClickParser"

    fun parse(clickedTexts: List<String>): ClickInfo {

        if (clickedTexts.isEmpty()) {
            return ClickInfo.UnhandledClick
        }

        Log.d(TAG, "Parsing click with texts: $clickedTexts")

        // We check against all texts found in the clicked node's hierarchy.
        for (text in clickedTexts) {
            // Using `when` with `contains` makes this much more robust.
            when {
                // CHANGE: Using `contains` instead of `equals`.
                // This will match "Accept" as well as "Accept (32s)".
                text.contains("Accept", ignoreCase = true) ||
                        text.contains("Add to route", ignoreCase = true) -> {
                    return ClickInfo.ButtonClick(ClickInfo.ClickType.ACCEPT_OFFER)
                }

                // CHANGE: Using `contains` for Decline as well.
                text.contains("Decline", ignoreCase = true) -> {
                    return ClickInfo.ButtonClick(ClickInfo.ClickType.DECLINE_OFFER)
                }

                // CHANGE: Using `startsWith` is more robust than a strict regex.
                // This will match "$10.50" as well as "$10.50 view details".
                text.trim().startsWith("$") -> {
                    return ClickInfo.ButtonClick(ClickInfo.ClickType.VIEW_PAY_DETAILS)
                }
            }
        }

        return ClickInfo.UnhandledClick
    }
}