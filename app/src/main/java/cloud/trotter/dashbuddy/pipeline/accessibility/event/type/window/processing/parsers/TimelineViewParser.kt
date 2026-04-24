package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.parsers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.ScreenInfo.TimelineTask
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenParser
import cloud.trotter.dashbuddy.util.UtilityFunctions
import timber.log.Timber
import javax.inject.Inject

class TimelineViewParser @Inject constructor() : ScreenParser {

    override val targetScreen = Screen.TIMELINE_VIEW

    private val taskPrefixes = listOf("Pickup for ", "Deliver to ", "Pickup from ")

    override fun parse(node: UiNode): ScreenInfo {
        val allTexts = node.allText

        // --- Earnings ---
        // "This dash" is followed immediately by the dollar amount in allText order.
        val dashIdx = allTexts.indexOfFirst { it.equals("This dash", ignoreCase = true) }
        val currentDashEarnings = if (dashIdx >= 0)
            UtilityFunctions.parseCurrency(allTexts.getOrNull(dashIdx + 1))
        else null

        val offerIdx = allTexts.indexOfFirst { it.equals("This offer", ignoreCase = true) }
        val currentOfferEarnings = if (offerIdx >= 0)
            UtilityFunctions.parseCurrency(allTexts.getOrNull(offerIdx + 1))
        else null

        // --- Dash end time ---
        val dashEndsAtText = allTexts.firstOrNull { it.startsWith("Dash ends at", ignoreCase = true) }
        val dashEndsAtMillis = dashEndsAtText?.let { UtilityFunctions.parseDeadlineMillis(it) }

        // --- Task chain ---
        // Each task is a pair of consecutive text nodes:
        //   "Pickup for Jane D" | "Deliver to Jane D"  (type+name)
        //   "by 18:42" | "by 18:09 • H-E-B" | "53 min to complete"  (deadline[• storeHint])
        // A "Current task" marker may appear as a child of the task entry node.
        val tasks = mutableListOf<TimelineTask>()
        val taskNodes = node.findNodes { n ->
            taskPrefixes.any { n.text?.startsWith(it, ignoreCase = true) == true }
        }

        for (taskNode in taskNodes) {
            val text = taskNode.text ?: continue
            val prefix = taskPrefixes.firstOrNull { text.startsWith(it, ignoreCase = true) } ?: continue
            val rawName = text.removePrefix(prefix).trim()
            val nameHash = if (rawName.isNotBlank()) UtilityFunctions.generateSha256(rawName) else null

            // The deadline node is the next sibling at the same depth (same parent).
            val parent = taskNode.parent
            val deadlineNode = if (parent != null) {
                val siblings = parent.children
                val idx = siblings.indexOf(taskNode)
                siblings.getOrNull(idx + 1)
            } else null

            val rawDeadline = deadlineNode?.text
            val deadlineParts = rawDeadline?.split(" • ", limit = 2)
            val deadlineText = deadlineParts?.getOrNull(0)?.trim()
            val storeHint = deadlineParts?.getOrNull(1)?.trim()

            val isCurrent = taskNode.findNode {
                it.text.equals("Current task", ignoreCase = true)
            } != null

            tasks += TimelineTask(
                taskType = prefix.trim(),
                nameHash = nameHash,
                deadlineText = deadlineText,
                storeHint = storeHint,
                isCurrent = isCurrent,
            )
        }

        Timber.d(
            "TimelineViewParser: dashEarnings=$currentDashEarnings, offerEarnings=$currentOfferEarnings, " +
                "endsAt='$dashEndsAtText', tasks=${tasks.size}"
        )

        return ScreenInfo.Timeline(
            screen = targetScreen,
            currentDashEarnings = currentDashEarnings,
            currentOfferEarnings = currentOfferEarnings,
            dashEndsAtText = dashEndsAtText,
            dashEndsAtMillis = dashEndsAtMillis,
            tasks = tasks,
        )
    }
}
