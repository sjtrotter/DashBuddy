package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.services.accessibility.screen.Screen
import cloud.trotter.dashbuddy.services.accessibility.screen.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.services.accessibility.UiNode

class SetDashEndTimeMatcher : ScreenMatcher {

    override val targetScreen = Screen.SET_DASH_END_TIME

    // Priority 1: Similar to Idle Map, this is a distinct screen.
    override val priority = 1

    override fun matches(node: UiNode): ScreenInfo? {
        // --- 1. MATCHING LOGIC ---

        // Criterion A: The specific Zone ID.
        // This is the strongest signal from your logs: id=starting_point_name
        val zoneNode = node.findNode {
            it.viewIdResourceName?.endsWith("starting_point_name") == true
        }

        // Criterion B: Contextual Text.
        // Ensure we are actually on the End Time selection, not just somewhere that lists a zone.
        // "Select end time" is a text view explicitly present in your log.
        val hasSelectTimeText = node.findNode {
            it.text?.contains("Select end time", ignoreCase = true) == true
        } != null

        // Criterion C: "Dashers needed until" (Optional but good confirmation)
        // Checks for the description text ID found in your logs.
        val hasTimeDescription = node.findNode {
            it.viewIdResourceName?.endsWith("end_time_description") == true
        } != null

        // Must have the Zone Node AND (Select Time Text OR Description)
        if (zoneNode == null || (!hasSelectTimeText && !hasTimeDescription)) {
            return null
        }

        // --- 2. PARSING LOGIC ---

        // Extract Zone Name directly from the node found in Criterion A.
        // The text will be something like "TX: Leon Valley"
        val zoneName = zoneNode.text

        // --- 3. RETURN RESULT ---

        // We return ScreenInfo.IdleMap as requested, so the DasherIdleOffline handler
        // can consume it and update the database.
        return ScreenInfo.IdleMap(
            screen = Screen.SET_DASH_END_TIME,
            zoneName = zoneName,
            dashType = null // Dash Type toggle is not visible on this screen
        )
    }
}