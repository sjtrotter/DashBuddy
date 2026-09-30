package cloud.trotter.dashbuddy.util

import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * A utility object for performing actions on AccessibilityNodeInfo objects.
 */
object AccNodeUtils {

    /**
     * #1149 — the most self → parent steps [resolveActionOwner] takes. The accessibility tree is
     * third-party input: a parent chain is bounded ingestion like everything else, and a cyclic or
     * pathologically deep chain resolves to NO owner (fail closed), never a hang.
     */
    const val MAX_OWNER_WALK = cloud.trotter.dashbuddy.domain.pipeline.NodeRef.MAX_OWNER_WALK // one owner, shared with bind time (#1149 L2)

    /**
     * #1149 — the one definition of "this node takes a click": `isClickable`, OR an advertised
     * [AccessibilityNodeInfo.ACTION_CLICK] in its action list (Compose and custom views often
     * advertise the action without setting the flag — TalkBack's own test). Its bind-time mirror is
     * `UiNode.takesClick` (`isClickable || hasClickAction`, #1149 review J2): the two MUST stay the
     * same predicate, or bind and fire disagree on which labels a control owns.
     */
    fun isActionClickable(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.actionList.orEmpty().any { it.id == AccessibilityNodeInfo.ACTION_CLICK }

    /**
     * #1149 — the ACTION OWNER of [node]: the first of self → parent → … that
     * [isActionClickable], within [MAX_OWNER_WALK] steps, with a cycle guard (`==` on the visited
     * nodes — [AccessibilityNodeInfo.equals] is window + source-node identity). Null when no owner
     * is reachable inside the bound; the caller must not tap anything then.
     *
     * The owner is what a tap actually lands on, so it is what the caller dedupes candidates by,
     * label-verifies and dispatches to — verification never runs on one node and the click on
     * another.
     */
    fun resolveActionOwner(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node
        val visited = ArrayList<AccessibilityNodeInfo>(4)
        var steps = 0
        while (current != null && steps < MAX_OWNER_WALK) {
            if (visited.any { it == current }) return null
            if (isActionClickable(current)) return current
            visited.add(current)
            current = current.parent
            steps++
        }
        return null
    }

    /**
     * STRICT CLICK (#425, #1149): clicks [owner] itself — no sibling fallback, no ancestor climb.
     *
     * The caller resolved [owner] via [resolveActionOwner], `refresh()`ed it, and label-verified
     * THAT refreshed state; a clickable sibling can be the opposite control (Accept sits beside
     * Decline in the offer footer), so falling laterally — or climbing past the verified node —
     * would tap something the verification never looked at.
     *
     * No second refresh here (#1149 review I1): a refresh between verification and dispatch could
     * rebind the node (a recycled row turning Decline → Accept) and click what was never verified.
     * The owner must still take a click and still belong to [expectedPackage].
     */
    fun clickNodeStrict(owner: AccessibilityNodeInfo?, expectedPackage: String): Boolean {
        if (owner == null) {
            Timber.tag("Effects").w("Cannot click: node is null.")
            return false
        }
        if (!isActionClickable(owner) || owner.packageName?.toString() != expectedPackage) {
            Timber.tag("Effects").w("Strict click: refusing — the target no longer takes a click in the scoped package (no sibling fallback).")
            return false
        }
        return owner.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
