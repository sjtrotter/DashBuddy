package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.matchers

import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.processing.ScreenMatcher
import timber.log.Timber
import javax.inject.Inject

class DashPausedMatcher @Inject constructor() : ScreenMatcher {

    override val targetScreen = Screen.DASH_PAUSED
    override val priority = 10

    override fun matches(node: UiNode): Screen? {
        val hasTitle = node.findNode { it.text.equals("Dash Paused", ignoreCase = true) } != null
        val hasResumeButton = node.findNode {
            it.viewIdResourceName?.endsWith("resumeButton") == true &&
                    it.contentDescription.equals("Resume dash", ignoreCase = true)
        } != null
        val hasTimer = node.findNode { it.viewIdResourceName?.endsWith("progress_number") == true } != null

        Timber.v("DashPaused check: title=$hasTitle, btn=$hasResumeButton, timer=$hasTimer")
        return if (hasTitle && hasResumeButton && hasTimer) targetScreen else null
    }
}