package cloud.trotter.dashbuddy.core.pipeline.rules

import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
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
 *
 * [defJson] retains the binding's raw JSON definition (the `bind` entry value:
 * `find` predicate + flags) so capability enumeration can content-pin consent
 * keys to it (#422/#425). Null only for programmatically-built bindings.
 */
data class Binding(
    val name: String,
    val find: (UiNode) -> UiNode?,
    val optional: Boolean = false,
    val defJson: kotlinx.serialization.json.JsonElement? = null,
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
 *
 * [redact] carries the rule-declared capture-redaction directives (#598) — a
 * screen-rule concept only. It is applied at capture time to the SERIALIZED
 * envelope; recognition, parse, and the state machine always run on the
 * original tree.
 */
data class CompiledRule<TInput>(
    val id: String,
    val priority: Int,
    val overrideable: Boolean = true,
    val bindings: List<Binding> = emptyList(),
    val branches: List<CompiledBranch<TInput>>,
    val redact: CompiledRedact = CompiledRedact.EMPTY,
    val notifRedact: CompiledNotifRedact = CompiledNotifRedact.EMPTY,
)

/** The notification fields a [CompiledNotifRedact] can mask (#620). */
enum class NotifField(val wire: String) {
    TITLE("title"),
    TEXT("text"),
    BIG_TEXT("bigText"),
    TICKER_TEXT("tickerText"),
    SUB_TEXT("subText"),
    ;

    companion object {
        fun fromWire(wire: String): NotifField? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A per-field notification redact masker (#620). Notifications are flat strings
 * (no tree), so masking is keyed by field name rather than a node predicate.
 */
sealed interface NotifFieldMask {
    /** Mask the whole field value, preserving an optional leading [keepPrefix]. */
    data class Whole(val keepPrefix: List<String> = emptyList()) : NotifFieldMask

    /**
     * Mask only the [group] capture of [regex] within the field, keeping the
     * rest — e.g. the STORE in "<name>'s order is ready for pickup at <store>"
     * (merchants are not PII).
     */
    data class RegexGroup(val regex: Regex, val group: Int) : NotifFieldMask
}

/**
 * The compiled `redact` block for a notification rule (#620) — the flat-string
 * analogue of [CompiledRedact]. Masks recognized customer PII (chat title/body,
 * order-ready customer name) in the serialized notification envelope only; the
 * ORIGINAL [RawNotificationData] drove recognition/parse and owns the dedup
 * `contentHash`. Reuses [CompiledRedact.mask] so notification masks get the same
 * `[redacted:<4hex>]` distinctness + fail-closed contract as screen redaction.
 */
data class CompiledNotifRedact(
    val fields: Map<NotifField, NotifFieldMask>,
) {
    fun isEmpty(): Boolean = fields.isEmpty()

    /**
     * Return a masked COPY of [raw] for envelope serialization. The original is
     * never mutated; callers must read the dedup `contentHash` off the ORIGINAL
     * (a masked copy recomputes a different hash).
     */
    fun apply(raw: RawNotificationData): RawNotificationData = raw.copy(
        title = maskField(NotifField.TITLE, raw.title),
        text = maskField(NotifField.TEXT, raw.text),
        bigText = maskField(NotifField.BIG_TEXT, raw.bigText),
        tickerText = maskField(NotifField.TICKER_TEXT, raw.tickerText),
        subText = maskField(NotifField.SUB_TEXT, raw.subText),
    )

    private fun maskField(field: NotifField, value: String?): String? {
        if (value == null) return null
        return when (val m = fields[field]) {
            null -> value
            is NotifFieldMask.Whole -> CompiledRedact.mask(value, m.keepPrefix)
            is NotifFieldMask.RegexGroup -> maskGroup(value, m.regex, m.group)
        }
    }

    private fun maskGroup(value: String, regex: Regex, group: Int): String {
        // FAIL CLOSED (#620 review F2b): if the capture regex drifts from the
        // require gate (the rule matched but this pattern doesn't, or the group is
        // absent), masking the group would ship the RAW field. A recognized frame
        // must never ship raw PII, so mask the WHOLE field instead.
        val match = regex.find(value) ?: return CompiledRedact.REDACTED
        val g = match.groups[group] ?: return CompiledRedact.REDACTED
        // Reuse the SSOT mask (no keepPrefix — the group IS the token to hash).
        val masked = CompiledRedact.mask(g.value, emptyList()) ?: CompiledRedact.REDACTED
        return value.replaceRange(g.range, masked)
    }

    companion object {
        val EMPTY = CompiledNotifRedact(emptyMap())
    }
}

/**
 * A single compiled `redact` directive (#598). When [find] matches a node, that
 * node's `text` and `contentDescription` are masked in the serialized capture
 * envelope only. [keepPrefix] preserves a leading marker label so a replayed
 * redacted capture still recognizes — e.g. "Deliver to <name>" is masked to
 * "Deliver to [redacted:<4hex>]", keeping the "Deliver to " require anchor while
 * the customer name is dropped. An address node (no marker) takes no keepPrefix
 * and is masked whole. The `<4hex>` distinctness suffix is #623 — see [mask].
 */
data class CompiledRedactEntry(
    val find: (UiNode) -> Boolean,
    val keepPrefix: List<String> = emptyList(),
)

/**
 * The compiled `redact` block for a screen rule (#598) — "recognition is data"
 * extended to privacy. The mechanism is DECLARED, not inferred from bindings:
 * most PII-bearing screens (dropoff_handoff, pickup_arrival) bind nothing that
 * could drive masking, so the rule states which nodes carry customer PII.
 *
 * [apply] returns a masked COPY of the tree for envelope serialization; the
 * original tree is never mutated (parent back-references are dropped on the
 * copy, which serialization ignores). The rebuild is top-down via
 * [UiNode.copy]; recognition, parse, and the dedup contentHash all run on the
 * original tree.
 */
data class CompiledRedact(
    val entries: List<CompiledRedactEntry>,
) {
    fun isEmpty(): Boolean = entries.isEmpty()

    /** Return a redacted copy of [tree] with matched node text/desc masked. */
    fun apply(tree: UiNode): UiNode = maskNode(tree)

    private fun maskNode(node: UiNode): UiNode {
        val match = entries.firstOrNull { it.find(node) }
        val maskedText =
            if (match != null) mask(node.text, match.keepPrefix) else node.text
        val maskedDesc =
            if (match != null) mask(node.contentDescription, match.keepPrefix) else node.contentDescription
        return node.copy(
            text = maskedText,
            contentDescription = maskedDesc,
            children = node.children.map { maskNode(it) },
        )
    }

    companion object {
        /**
         * Fail-closed mask written when the distinctness hash can't be computed
         * (#362/#623): a redacted node still ships redacted, never the raw value.
         */
        const val REDACTED = "[redacted]"
        val EMPTY = CompiledRedact(emptyList())

        /**
         * Mask [value], preserving a leading [keepPrefix] marker (#598), and
         * append a per-customer distinctness suffix (#623): the masked portion
         * becomes `[redacted:<4hex>]`, where `<4hex>` is the first four hex chars
         * of the sha256 of the keepPrefix-STRIPPED, TRIMMED token.
         *
         * Frame-invariance: the token hashed is the customer string itself (after
         * stripping the marker prefix and trimming), NOT the whole node text — so
         * the SAME customer under different frame prefixes ("Deliver to X" vs
         * "Heading to X" vs "Verify items for X") redacts to the SAME suffix, and
         * two DIFFERENT customers redact to DIFFERENT suffixes. The strip+trim
         * mirrors the parse's `stripPrefixes`/`trim`/`sha256` chain, so the suffix
         * equals the first 4 hex of the `customerNameHash` the parse already
         * persists — 16 bits of an already-one-way hash, no new information (#623).
         *
         * FAIL CLOSED (#362): if [sha256OrNull] returns null, emit the plain
         * [REDACTED] constant — never the raw [value]. The "[redacted" prefix is
         * kept so any `contains("[redacted")`/`startsWith` check (the #624
         * backstop, any test scanner) still classifies the output as redacted.
         *
         * `internal` (not private) so the #620 notification per-field maskers
         * reuse this ONE definition (SSOT) instead of copying it.
         */
        internal fun mask(value: String?, keepPrefix: List<String>): String? {
            if (value == null) return null
            val prefix = keepPrefix.firstOrNull { value.startsWith(it, ignoreCase = true) }
            val token = if (prefix != null) value.substring(prefix.length).trim() else value.trim()
            val hex = sha256OrNull(token)?.take(4)
            val masked = if (hex != null) "[redacted:$hex]" else REDACTED
            return if (prefix != null) prefix + masked else masked
        }
    }
}

/**
 * Unified result of matching any rule type (screen, click, or notification).
 * The consumer uses [shape] + [fields] with [ParsedFieldsFactory] to produce
 * typed `ParsedFields`.
 *
 * [targets] are the rule's resolved bindings as `NodeRef` fingerprints
 * (#425) — recognition-layer data the app-owned `RuleAction` registry may
 * later aim a tap with. Only bindings that resolved to a node appear.
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
    val targets: Map<String, cloud.trotter.dashbuddy.domain.pipeline.NodeRef> = emptyMap(),
    val transitionOverrides: Map<TransitionTrigger, List<RequestedEffect>> = emptyMap(),
)

/**
 * A compiled effect from a rule's `effects:` (or legacy `actions:`) block.
 *
 * Purely observational/app-internal (#425): actuating verbs left the rule
 * vocabulary, so compiled effects carry no target and no consent key. Taps
 * are app-owned `RuleAction`s aimed by the rule's *bindings* instead.
 */
data class CompiledEffect(
    val verb: cloud.trotter.dashbuddy.domain.pipeline.EffectVerb,
    val args: Map<String, String> = emptyMap(),
    val onlyIf: cloud.trotter.dashbuddy.domain.pipeline.ParsedFieldsGate? = null,
    val dedupeKey: String? = null,
    val throttleMs: Long? = null,
)
