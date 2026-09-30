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
 * click action. One definition for the mapper and the executor (`AccNodeUtils.isActionClickable`);
 * its bind-time mirror is `UiNode.takesClick` (`isClickable || hasClickAction`).
 */
fun AccessibilityNodeInfo.takesClick(): Boolean = isClickable || hasClickAction()
