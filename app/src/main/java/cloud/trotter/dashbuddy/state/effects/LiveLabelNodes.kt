package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.core.pipeline.accessibility.mapper.takesClick
import android.view.accessibility.AccessibilityNodeInfo
import cloud.trotter.dashbuddy.domain.pipeline.LabelNode
import cloud.trotter.dashbuddy.util.AccNodeUtils

/*
 * #1149 — the fire-time LabelNode adapter over a live node, and the shared own-label read: moved out of
 * UiInteractionHandler (review S10). Pure move.
 */

/** A live node as a [LabelNode]: children are fetched lazily, one `getChild` per slot the horizon touches. */
internal class LiveLabelNode(private val node: AccessibilityNodeInfo, private val expectedPackage: String) : LabelNode {
    override val foreign: Boolean by lazy { node.packageName?.toString() != expectedPackage }
    // P4: a foreign node's text/description is never touched.
    override val ownLabels: List<String> by lazy { if (foreign) emptyList() else ownLabelsOf(node) }
    override val takesClick: Boolean get() = node.takesClick()
    override val unreadableChildren: Int get() = 0
    override fun children(): List<LabelNode?> = object : AbstractList<LabelNode?>() {
        override val size: Int = node.childCount.coerceAtLeast(0)
        override fun get(index: Int): LabelNode? = node.getChild(index)?.let { LiveLabelNode(it, expectedPackage) }
    }
}

/** A live node's own non-blank text and contentDescription — the [LabelNode.ownLabels] of both fire-time adapters. */
internal fun ownLabelsOf(node: AccessibilityNodeInfo): List<String> = listOfNotNull(
    node.text?.toString(),
    node.contentDescription?.toString(),
) // raw: LabelHorizon caps then blank-filters (#1149 R2)
