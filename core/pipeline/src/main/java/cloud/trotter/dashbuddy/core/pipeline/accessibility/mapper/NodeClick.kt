package cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper

import android.view.accessibility.AccessibilityNodeInfo

/**
 * #1149 review P6 — the node ADVERTISES the standard click action. Read from the legacy action
 * bitmask (`getActions()`), which carries every standard action, instead of materializing
 * `actionList` per node per frame. The mapper stamps it as `UiNode.hasClickAction`.
 */
@Suppress("DEPRECATION")
fun AccessibilityNodeInfo.hasClickAction(): Boolean = (actions and AccessibilityNodeInfo.ACTION_CLICK) != 0

/**
 * #1149 review P6 — THE live "this node takes a click" predicate: `isClickable` OR an advertised
 * click action. One definition for the mapper and the executor (`AccNodeUtils`, the finders and the label adapters);
 * its bind-time mirror is `UiNode.takesClick` (`isClickable || hasClickAction`).
 */
fun AccessibilityNodeInfo.takesClick(): Boolean = isClickable || hasClickAction()

/**
 * #1147 — the label attached to this node's `ACTION_CLICK` entry, raw (uncapped), or null. THE live
 * definition for both the mapper (`UiNode.clickActionLabel`, capped there) and the fire-time label
 * adapters (`ownLabelsOf`), so bind and fire read the same string. `getActionList()` reads the action
 * list the node was delivered with (no IPC), and is touched ONLY when the bitmask already advertises a
 * click — the #1149 P6 cost stays off every other node.
 */
fun AccessibilityNodeInfo.clickActionLabelOrNull(): String? = clickActionLabelOrNull(hasClickAction())

/**
 * #1147 review Z6 — the same read for a caller that has ALREADY derived [hasClick] from the bitmask
 * (the mapper), so `getActions()` is evaluated once per node.
 */
fun AccessibilityNodeInfo.clickActionLabelOrNull(hasClick: Boolean): String? =
    if (!hasClick) null
    else actionList?.firstOrNull { it.id == AccessibilityNodeInfo.ACTION_CLICK }?.label?.toString()
