package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode
import cloud.trotter.dashbuddy.domain.state.ParsedFields

/** Resolved bindings: name -> found UiNode (null if optional and not found). */
typealias Bindings = Map<String, UiNode?>

/**
 * A named binding declaration compiled from a `bind:` block.
 *
 * @param name    The `$name` reference usable in subsequent phases.
 * @param find    Searches the tree (or a `from:` node) for a matching node.
 * @param optional If true, a null result doesn't disqualify the rule.
 */
data class Binding(
    val name: String,
    val find: (UiNode) -> UiNode?,
    val optional: Boolean = false,
)

/**
 * A single branch within a [CompiledScreenRule].
 *
 * The 5-phase pipeline runs in order per branch:
 * 1. **bind** — resolve branch-scoped bindings
 * 2. **reject** — any true → skip this branch
 * 3. **require** — must return true for the branch to match
 * 4. **parse** — extract typed fields from the tree
 * 5. **validate** — assert parsed field constraints
 */
data class CompiledBranch(
    val target: String,
    val bindings: List<Binding> = emptyList(),
    val rejectChecks: List<(UiNode, Bindings) -> Boolean> = emptyList(),
    val requireCheck: (UiNode, Bindings) -> Boolean,
    val parser: ((UiNode, Bindings) -> Map<String, Any?>) = { _, _ -> emptyMap() },
    val parseShape: String? = null,
    val validators: List<(Map<String, Any?>) -> ValidateOutcome> = emptyList(),
    val effects: List<CompiledEffect> = emptyList(),
    val transitionOverrides: Map<String, List<CompiledEffect>> = emptyMap(),
    val flow: Flow? = null,
    val modeHint: Mode? = null,
)

/**
 * A compiled screen rule. Single-target rules normalise to a one-element [branches] list.
 *
 * Rules are sorted ascending by [priority] (lower = evaluated first).
 * Rule-level [bindings] are shared across all branches and resolved once per rule.
 */
data class CompiledScreenRule(
    val id: String,
    val priority: Int,
    val overrideable: Boolean,
    val bindings: List<Binding> = emptyList(),
    val branches: List<CompiledBranch>,
)

/**
 * Result of matching a screen rule via the 5-phase pipeline.
 */
data class ScreenMatchResult(
    val ruleId: String,
    val target: String,
    val flow: Flow?,
    val modeHint: Mode?,
    val parsed: ParsedFields,
    val effects: List<cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect> = emptyList(),
    val transitionOverrides: Map<cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger, List<cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect>> = emptyMap(),
)

/**
 * A compiled click rule. Click predicates operate on a single [UiNode] (no tree traversal).
 * [intentFactory] returns the snake_case intent string for the matched click.
 */
data class CompiledClickRule(
    val id: String,
    val priority: Int,
    val overrideable: Boolean,
    val condition: (UiNode) -> Boolean,
    val intentFactory: (UiNode) -> String,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
    val screenConstraint: String? = null,
)

/**
 * A compiled notification rule. [classify] handles both matching and extraction in one step,
 * returning null if the rule does not match.
 */
data class CompiledNotificationRule(
    val id: String,
    val priority: Int,
    val overrideable: Boolean,
    val classify: (RawNotificationData) -> NotificationClassifyResult?,
    val shape: String? = null,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
)

/**
 * Result of matching a click rule.
 */
data class ClickMatchResult(
    val ruleId: String,
    val intent: String,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
)

/**
 * Intermediate result from a notification rule's classify lambda.
 */
data class NotificationClassifyResult(
    val intent: String,
    val fields: Map<String, Any?> = emptyMap(),
)

/**
 * Result of matching a notification rule.
 */
data class NotificationMatchResult(
    val ruleId: String,
    val intent: String,
    val fields: Map<String, Any?> = emptyMap(),
    val shape: String? = null,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
)

/**
 * A compiled effect from a rule's `effects:` (or legacy `actions:`) block.
 *
 * At match time, [targetBindName] is resolved against the current bindings
 * to produce a [NodeRef] on the emitted [RequestedEffect]. For non-target
 * verbs (everything except [EffectVerb.CLICK]), [targetBindName] is null.
 */
data class CompiledEffect(
    val verb: cloud.trotter.dashbuddy.domain.pipeline.EffectVerb,
    val targetBindName: String? = null,
    val args: Map<String, String> = emptyMap(),
    val onlyIf: cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate? = null,
    val dedupeKey: String? = null,
    val throttleMs: Long? = null,
)
