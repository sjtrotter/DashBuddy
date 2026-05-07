package cloud.trotter.dashbuddy.pipeline.accessibility.mapper

import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.BuildConfig
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import timber.log.Timber

fun AccessibilityNodeInfo?.toUiNode(parentUiNode: UiNode? = null, depth: Int = 0): UiNode? {
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

    val childCount = this.childCount
    var nullChildren = 0

    for (i in 0 until childCount) {
        val childAccNode = this.getChild(i)
        if (childAccNode != null) {
            childAccNode.toUiNode(currentUiNode, depth + 1)?.let { childUiNode ->
                currentUiNode.children.add(childUiNode)
            }
        } else {
            nullChildren++
        }
    }

    // In debug builds, log when children are reported but inaccessible
    if (BuildConfig.DEBUG && nullChildren > 0) {
        Timber.w(
            "👻 NULL CHILDREN: %d/%d null at depth=%d class=%s id=%s",
            nullChildren, childCount, depth,
            this.className, this.viewIdResourceName
        )
    }

    return currentUiNode
}