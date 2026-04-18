package cloud.trotter.dashbuddy.pipeline.accessibility.mapper

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode

fun AccessibilityNodeInfo?.toUiNode(parentUiNode: UiNode? = null): UiNode? {
    if (this == null) return null

    val bounds = Rect()
    this.getBoundsInScreen(bounds)

    val currentUiNode = UiNode(
        text = this.text?.toString(),
        contentDescription = this.contentDescription?.toString(),
        stateDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            this.stateDescription?.toString()
        } else null,
        viewIdResourceName = this.viewIdResourceName,
        className = this.className?.toString(),
        isClickable = this.isClickable,
        isEnabled = this.isEnabled,
        isChecked = this.checked,
        boundsInScreen = bounds.toBoundingBox(),
        parent = parentUiNode
    )

    for (i in 0 until this.childCount) {
        val childAccNode = this.getChild(i)
        childAccNode?.toUiNode(currentUiNode)?.let { childUiNode ->
            currentUiNode.children.add(childUiNode)
        }
    }

    return currentUiNode
}