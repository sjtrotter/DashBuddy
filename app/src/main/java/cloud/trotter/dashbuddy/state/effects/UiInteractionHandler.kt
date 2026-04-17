package cloud.trotter.dashbuddy.state.effects

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.pipeline.accessibility.input.AccessibilitySource
import cloud.trotter.dashbuddy.pipeline.accessibility.mapper.toBoundingBox
import cloud.trotter.dashbuddy.util.AccNodeUtils
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UiInteractionHandler @Inject constructor(
    private val accessibilitySource: AccessibilitySource
) {

    fun performClick(templateNode: UiNode, description: String) {
        Timber.i("UiInteractionHandler: Attempting surgical strike ($description)")

        val liveRoot = accessibilitySource.getLiveNativeRoot()
        if (liveRoot == null) {
            Timber.w("Failed to click: Live root window is null.")
            return
        }

        val candidates = mutableListOf<AccessibilityNodeInfo>()

        // FIX 1: Extract to local variables to satisfy the cross-module smart cast
        val targetId = templateNode.viewIdResourceName
        val targetText = templateNode.text

        if (!targetId.isNullOrEmpty()) {
            candidates.addAll(liveRoot.findAccessibilityNodeInfosByViewId(targetId))
        } else if (!targetText.isNullOrEmpty()) {
            candidates.addAll(liveRoot.findAccessibilityNodeInfosByText(targetText))
        }

        val exactMatch = candidates.find { nativeNode ->
            val liveBounds = Rect()
            nativeNode.getBoundsInScreen(liveBounds)
            liveBounds.toBoundingBox() == templateNode.boundsInScreen
        }

        if (exactMatch != null) {
            AccNodeUtils.clickNode(exactMatch)
        } else {
            Timber.w("Could not find a live node matching the template for: $description")
        }
    }
}