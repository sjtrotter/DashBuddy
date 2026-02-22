package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.model.UiNode
import cloud.trotter.dashbuddy.util.AccNodeUtils
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiInteractionHandler @Inject constructor(
    private val accessibilitySource: AccessibilitySource
) {

    /**
     * Takes a pure data template, finds its live native counterpart, and clicks it.
     */
    fun performClick(templateNode: UiNode, description: String) {
        Timber.i("UiInteractionHandler: Attempting surgical strike ($description)")

        // 1. Get the live native root directly from the OS
        val liveRoot = accessibilitySource.getLiveNativeRoot()
        if (liveRoot == null) {
            Timber.w("Failed to click: Live root window is null.")
            return
        }

        // 2. Fast Native Search
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        if (!templateNode.viewIdResourceName.isNullOrEmpty()) {
            candidates.addAll(liveRoot.findAccessibilityNodeInfosByViewId(templateNode.viewIdResourceName))
        } else if (!templateNode.text.isNullOrEmpty()) {
            candidates.addAll(liveRoot.findAccessibilityNodeInfosByText(templateNode.text))
        }

        // 3. Exact Match Filter (Use Bounds as the fingerprint)
        val exactMatch = candidates.find { nativeNode ->
            val liveBounds = Rect()
            nativeNode.getBoundsInScreen(liveBounds)
            liveBounds == templateNode.boundsInScreen
        }

        // 4. Delegate to your existing utility!
        if (exactMatch != null) {
            AccNodeUtils.clickNode(exactMatch)
        } else {
            Timber.w("Could not find a live node matching the template for: $description")
        }
    }
}