package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.ClickInfo
import cloud.trotter.dashbuddy.domain.model.accessibility.Screen
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.NotificationInfo
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode

/**
 * A single branch within a [CompiledScreenRule].
 *
 * @param target    The [Screen] enum value to return on match.
 * @param guards    If ANY guard lambda returns true, this branch is skipped.
 * @param condition The main predicate — must return true for the branch to match.
 * @param flow      ADR-0005 flow value from the rule's `state:` block; null = no state contribution.
 * @param modeHint  ADR-0005 mode hint from the rule's `state:` block; null = no mode signal.
 */
data class CompiledBranch(
    val target: Screen,
    val guards: List<(UiNode) -> Boolean>,
    val condition: (UiNode) -> Boolean,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
)

/**
 * A compiled screen rule. Single-target rules normalise to a one-element [branches] list.
 *
 * Rules are sorted ascending by [priority] (lower = evaluated first).
 * [flow] and [modeHint] are rule-level defaults inherited by branches that
 * don't override them.
 */
data class CompiledScreenRule(
    val id: String,
    val priority: Int,
    val overrideable: Boolean,
    val branches: List<CompiledBranch>,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
)

/**
 * A compiled click rule. Click predicates operate on a single [UiNode] (no tree traversal).
 *
 * [factory] is called with the matched node to produce the [ClickInfo] result.
 */
data class CompiledClickRule(
    val id: String,
    val priority: Int,
    val overrideable: Boolean,
    val condition: (UiNode) -> Boolean,
    val factory: (UiNode) -> ClickInfo,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
)

/**
 * A compiled notification rule. [classify] handles both matching and extraction in one step,
 * returning null if the rule does not match.
 */
data class CompiledNotificationRule(
    val id: String,
    val priority: Int,
    val overrideable: Boolean,
    val classify: (RawNotificationData) -> NotificationInfo?,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
)
