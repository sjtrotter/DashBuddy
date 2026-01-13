package cloud.trotter.dashbuddy.pipeline.recognition.matchers

import cloud.trotter.dashbuddy.pipeline.recognition.Screen
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenInfo
import cloud.trotter.dashbuddy.pipeline.recognition.ScreenMatcher
import cloud.trotter.dashbuddy.services.accessibility.UiNode
import cloud.trotter.dashbuddy.log.Logger as Log

class DashPausedMatcher : ScreenMatcher {

    override val targetScreen = Screen.DASH_PAUSED
    override val priority = 10 // High priority (Specific state)

    override fun matches(node: UiNode): ScreenInfo? {
        // --- 1. MATCHING LOGIC ---
        // We need strong anchors to confirm we are paused.

        // Anchor A: The specific Title
        val hasTitle = node.findNode {
            it.text.equals("Dash Paused", ignoreCase = true)
        } != null

        // Anchor B: The Resume Button (ID is very reliable here)
        val hasResumeButton = node.findNode {
            it.viewIdResourceName?.endsWith("resumeButton") == true &&
                    it.contentDescription.equals("Resume dash", ignoreCase = true)
        } != null

        // Anchor C: The Timer Node (ID matches your log)
        val progressNumberNode =
            node.findNode { it.viewIdResourceName?.endsWith("progress_number") == true }

        Log.d(
            "DashPausedMatcher",
            "Checking... Title=$hasTitle, Btn=$hasResumeButton, Timer=$progressNumberNode"
        )
        if (!hasTitle || !hasResumeButton || progressNumberNode == null) {
            return null
        }

        // --- 2. PARSING LOGIC ---

        val timeString = progressNumberNode.text ?: "35:00"
        val remainingMillis = parseTimeString(timeString)

        return ScreenInfo.DashPaused(
            screen = targetScreen,
            remainingMillis = remainingMillis,
            rawTimeText = timeString
        )
    }

    private fun parseTimeString(time: String): Long {
        return try {
            val parts = time.split(":")
            // Handle "MM:SS"
            if (parts.size == 2) {
                val minutes = parts[0].toLong()
                val seconds = parts[1].toLong()
                (minutes * 60 * 1000) + (seconds * 1000)
            } else {
                35 * 60 * 1000L // Fallback
            }
        } catch (_: Exception) {
            35 * 60 * 1000L // Fallback
        }
    }
}