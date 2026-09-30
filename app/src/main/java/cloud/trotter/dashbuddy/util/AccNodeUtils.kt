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
    const val MAX_OWNER_WALK = 32

    /**
     * #1149 — the one definition of "this node takes a click": `isClickable`, OR an advertised
     * [AccessibilityNodeInfo.ACTION_CLICK] in its action list (Compose and custom views often
     * advertise the action without setting the flag — TalkBack's own test).
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
     * The caller resolved [owner] via [resolveActionOwner] and label-verified THAT node's
     * subtree; a clickable sibling can be the opposite control (Accept sits beside Decline in the
     * offer footer), so falling laterally — or climbing past the verified node — would tap
     * something the verification never looked at.
     *
     * Freshness (#1149): the owner is [AccessibilityNodeInfo.refresh]ed immediately before
     * dispatch. A node whose view is gone (a sheet dismissed between resolve and tap) refuses the
     * refresh → no click. After the refresh the owner must still take a click.
     */
    fun clickNodeStrict(owner: AccessibilityNodeInfo?): Boolean {
        if (owner == null) {
            Timber.tag("Effects").w("Cannot click: node is null.")
            return false
        }
        if (!owner.refresh()) {
            Timber.tag("Effects").w("Strict click: stale node (refresh failed) — refusing (fail closed).")
            return false
        }
        if (!isActionClickable(owner)) {
            Timber.tag("Effects").w("Strict click: refusing — the refreshed target no longer takes a click (no sibling fallback).")
            return false
        }
        return owner.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
