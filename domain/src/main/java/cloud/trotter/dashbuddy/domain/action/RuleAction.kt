package cloud.trotter.dashbuddy.domain.action

/**
 * App-owned vocabulary of actions the state machine may perform on a
 * third-party platform app (#425, #422 PR 2).
 *
 * This is the actuation half of the recognition/actuation split: rulesets
 * (untrusted, forkable — #192) expose named **target bindings** (pure data —
 * "this node is the decline button"); the app decides *whether* to act and
 * executes the tap. A rule can never declare an action — the `click` effect
 * verb was removed and rejected at compile time. A platform supports an
 * action iff its ruleset binds [targetBindName] on the relevant screen;
 * a missing binding means the action is unavailable there (fail to manual).
 *
 * [verification] is the app-owned trust anchor for the tap itself: since the
 * binding definition comes from the (future-CDN, untrusted) ruleset, the
 * executor re-checks the *resolved live node* against this expectation at
 * tap time — see `UiInteractionHandler.performVerifiedClick`. Consent
 * (#422/#417) is keyed per (rule, action, binding definition) via
 * `RuleCapability`.
 */
enum class RuleAction(
    val wire: String,
    /** Well-known bind name a platform ruleset exposes for this action. */
    val targetBindName: String,
    /** Tap-time expectation the resolved live node must satisfy. */
    val verification: TargetExpectation,
) {
    /**
     * Tap the offer screen's Accept button. "Add to route" is DoorDash's
     * stacked-offer variant of the same control.
     */
    ACCEPT_OFFER(
        wire = "accept_offer",
        targetBindName = "acceptButton",
        verification = TargetExpectation(labelPattern = Regex("(?i)\\baccept\\b|\\badd to route\\b")),
    ),

    /**
     * Tap the offer screen's initial Decline button. The platform's confirm
     * dialog (if any) is left to the user — auto-confirm is #110 Stage 2c.
     */
    DECLINE_OFFER(
        wire = "decline_offer",
        targetBindName = "declineButton",
        verification = TargetExpectation(labelPattern = Regex("(?i)\\bdecline\\b")),
    ),

    /**
     * Tap the chevron that expands the post-delivery pay breakdown so the
     * parser can read the line items. The control carries no text, so the
     * expectation is package-scope only — acceptable for a read-only,
     * low-stakes expansion tap.
     */
    EXPAND_EARNINGS(
        wire = "expand_earnings",
        targetBindName = "expandButton",
        verification = TargetExpectation(labelPattern = null),
    ),
    ;

    companion object {
        private val byWire: Map<String, RuleAction> = entries.associateBy { it.wire }

        /** Bind-name → action lookup used by capability enumeration (#422). */
        val byTargetBindName: Map<String, RuleAction> = entries.associateBy { it.targetBindName }

        fun fromWire(wire: String): RuleAction? = byWire[wire]
    }
}

/**
 * App-owned verification an action's resolved target must pass at tap time.
 *
 * The binding *definition* is consent-pinned at load (#422), but a
 * byte-identical definition can resolve to a different control after a
 * platform UI update — and the screen can change between decision and tap.
 * This expectation is the runtime check that is independent of the ruleset:
 * the executor collects the candidate node's own text/contentDescription plus
 * its descendants' (platform buttons often label via a child TextView) and
 * requires a [labelPattern] match. `labelPattern == null` means the action is
 * label-free (e.g. an icon-only chevron) and package scoping is the only bar.
 *
 * Note: patterns match the *platform app's* display language (en-US for the
 * alpha); locale-aware patterns are tracked with the i18n work (#428).
 */
data class TargetExpectation(
    val labelPattern: Regex?,
) {
    /** True if [labels] (node + descendant texts) satisfy this expectation. */
    fun matchesLabels(labels: List<String>): Boolean =
        labelPattern == null || labels.any { labelPattern.containsMatchIn(it) }
}
