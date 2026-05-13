package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.pipeline.RequestedEffect
import cloud.trotter.dashbuddy.domain.pipeline.TransitionTrigger
import cloud.trotter.dashbuddy.domain.state.Flow
import cloud.trotter.dashbuddy.domain.state.Mode

/**
 * The context in which a rule is compiled and evaluated.
 * Determines predicate vocabulary, parse input source, and binding support.
 */
enum class RuleContext {
    /** Screen rules: tree predicates, UiNode tree input, binding support. */
    SCREEN,
    /** Click rules: node predicates, single UiNode input. */
    CLICK,
    /** Notification rules: notification predicates, RawNotificationData input. */
    NOTIFICATION,
}

/** Resolved bindings: name -> found UiNode (null if optional and not found). */
typealias Bindings = Map<String, UiNode?>

/**
 * A named binding declaration compiled from a `bind:` block.
 * Bindings are a screen-rule concept; click and notification rules have no bindings.
 */
data class Binding(
    val name: String,
    val find: (UiNode) -> UiNode?,
    val optional: Boolean = false,
)

/**
 * A single branch within a [CompiledRule]. Generic over the input type.
 *
 * The pipeline runs in order per branch:
 * 1. **bind** — resolve branch-scoped bindings (screen rules only)
 * 2. **reject** — any true → skip this branch
 * 3. **require** — must return true for the branch to match
 * 4. **parse** — extract typed fields from the input
 * 5. **validate** — assert parsed field constraints
 *
 * For screen rules, `TInput` is [UiNode] (full tree).
 * For click rules, `TInput` is [UiNode] (single tapped node).
 * For notification rules, `TInput` is `RawNotificationData`.
 */
data class CompiledBranch<TInput>(
    val predicate: ((TInput) -> Boolean)? = null,
    val rejectChecks: List<(TInput) -> Boolean> = emptyList(),
    val parser: ((TInput, Bindings) -> Map<String, Any?>) = { _, _ -> emptyMap() },
    val validators: List<(Map<String, Any?>) -> ValidateOutcome> = emptyList(),
    val effects: List<CompiledEffect> = emptyList(),
    val bindings: List<Binding> = emptyList(),
    val shape: String? = null,
    val intent: String? = null,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
    val outcomes: Set<Flow>? = null,
    val screenIs: String? = null,
    val transitionOverrides: Map<String, List<CompiledEffect>> = emptyMap(),
)

/**
 * A compiled rule. Generic over the input type (`TInput`).
 *
 * Single-target rules normalise to a one-element [branches] list.
 * Rules are sorted ascending by [priority] (lower = evaluated first).
 * Rule-level [bindings] are shared across all branches and resolved once per rule.
 */
data class CompiledRule<TInput>(
    val id: String,
    val priority: Int,
    val overrideable: Boolean = true,
    val bindings: List<Binding> = emptyList(),
    val branches: List<CompiledBranch<TInput>>,
)

/**
 * Unified result of matching any rule type (screen, click, or notification).
 * The consumer uses [shape] + [fields] with [ParsedFieldsFactory] to produce
 * typed `ParsedFields`.
 */
data class RuleMatchResult(
    val ruleId: String,
    val intent: String,
    val shape: String? = null,
    val flow: Flow? = null,
    val modeHint: Mode? = null,
    val outcomes: Set<Flow>? = null,
    val fields: Map<String, Any?> = emptyMap(),
    val effects: List<RequestedEffect> = emptyList(),
    val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>> = emptyMap(),
)

/**
 * A compiled effect from a rule's `effects:` (or legacy `actions:`) block.
 *
 * At match time, [targetBindName] is resolved against the current bindings
 * to produce a `NodeRef` on the emitted [RequestedEffect]. For non-target
 * verbs (everything except `EffectVerb.CLICK`), [targetBindName] is null.
 */
data class CompiledEffect(
    val verb: cloud.trotter.dashbuddy.domain.pipeline.EffectVerb,
    val targetBindName: String? = null,
    val args: Map<String, String> = emptyMap(),
    val onlyIf: cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate? = null,
    val dedupeKey: String? = null,
    val throttleMs: Long? = null,
)
