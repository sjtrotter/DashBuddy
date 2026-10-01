package cloud.trotter.dashbuddy.domain.capability

import cloud.trotter.dashbuddy.domain.action.RuleAction

/**
 * One app-owned action a specific rule's target binding enables — the unit of
 * user consent (#422, refit by #425). A rule that binds a well-known action
 * target (`acceptButton`, `declineButton`, `expandButton`) produces one of
 * these per (rule, action) at rule-load time, and the user grants or denies
 * each individually.
 *
 * Rules declare enabled actions and expose target *bindings* (data), while
 * the app's [RuleAction] registry decides and performs taps (#1167). What
 * the user consents to is therefore "the app may perform [action] on this
 * platform, aimed by this rule's binding."
 *
 * Driver: Google's accessibility policy requires granular per-automation
 * consent, and once recognition rules are delivered from a forkable source
 * (#192) every rule is untrusted input. Consent is therefore **uniform** — it
 * never depends on where the rule came from; [source] is recorded only so the
 * consent UI can show provenance.
 *
 * ## Why [key] is content-pinned (the security property)
 * [key] hashes `(ruleId, action, the SORTED SET of every branch's binding definition)`
 * (#1167), including rule-level bindings. Repointing a predicate in ANY branch
 * changes [key], so the old grant no longer covers it and it re-enters consent.
 * Adding a layout changes the key (one re-prompt); reordering branches does not.
 * This defeats silent escalation via an update to an already-approved rule.
 *
 * The definition pin is the *static* half of the defense; the *dynamic* half
 * is tap-time verification ([RuleAction.verification] + package scoping in
 * the executor), which checks what the definition actually resolves to on the
 * live screen. Both are required: a byte-stable definition can land on a
 * different control after a platform UI update.
 */
data class RuleCapability(
    val ruleId: String,
    val action: RuleAction,
    /** The rule's bind name for the target (e.g. "declineButton"); display only. */
    val targetBindName: String,
    /** Content-pinned consent key — see the class doc. The grant store keys on this. */
    val key: String,
    /** Where the rule was loaded from (asset path, CDN url, fork id); display only. */
    val source: String,
    /**
     * How many DISTINCT binding definitions across the rule's branches aim this action — the number
     * of card layouts one grant covers (#1167). Display only; it is not part of the key's identity
     * beyond the definitions themselves.
     */
    val layoutCount: Int = 1,
)
