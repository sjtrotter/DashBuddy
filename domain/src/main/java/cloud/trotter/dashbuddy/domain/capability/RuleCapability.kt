package cloud.trotter.dashbuddy.domain.capability

import cloud.trotter.dashbuddy.domain.pipeline.EffectVerb

/**
 * One actuating action a specific rule wants to perform — the unit of user
 * consent (#422). A rule that declares an automation (today a `click`; future
 * swipes/scrolls/etc.) produces one of these per actuating effect at rule-load
 * time, and the user grants or denies each individually.
 *
 * Driver: Google's accessibility policy requires granular per-automation
 * consent, and once recognition rules are delivered from a forkable source
 * (#192) every rule is untrusted input. Consent is therefore **uniform** — it
 * never depends on where the rule came from; [source] is recorded only so the
 * consent UI can show provenance.
 *
 * ## Why [key] is content-pinned (the security property)
 * [key] is a hash of `(ruleId, verb, the binding DEFINITION the action targets)`
 * — computed once at compile (see the rule engine) and carried unchanged to the
 * execution gate, so the two always agree. It is pinned to the *matching
 * predicate that selects the node the rule will act on*, not to the rule id or
 * the bind name. So a remote update that keeps the rule id and bind name but
 * quietly repoints `declineButton`'s predicate at the **Accept** button changes
 * the definition → changes [key] → the old grant no longer covers it and it
 * re-enters consent. This defeats silent escalation via an update to an
 * already-approved rule.
 */
data class RuleCapability(
    val ruleId: String,
    val verb: EffectVerb,
    /** The rule's bind name for the target (e.g. "declineButton"); display only. */
    val targetBindName: String?,
    /** Content-pinned consent key — see the class doc. The grant store keys on this. */
    val key: String,
    /** Where the rule was loaded from (asset path, CDN url, fork id); display only. */
    val source: String,
)
