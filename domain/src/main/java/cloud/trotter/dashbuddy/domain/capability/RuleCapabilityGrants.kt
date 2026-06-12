package cloud.trotter.dashbuddy.domain.capability

import cloud.trotter.dashbuddy.domain.action.RuleAction
import kotlinx.coroutines.flow.StateFlow

/**
 * The consent state for rule-enabled actions (#417/#422): which
 * [RuleCapability] keys the user has granted, joined with the capabilities
 * the currently loaded rulesets enumerate.
 *
 * This is the read/reconcile contract the rule loader and the side-effect
 * engine see; the implementation (`:core:data`) persists grants in
 * `:core:datastore` and holds the enumeration in memory. The consent UI
 * (#422 PR 3) builds on the same store.
 *
 * Fail-closed posture (dev principle 6): an action whose capability cannot
 * be positively resolved to a granted key — unknown rule, missing
 * enumeration, unreadable store — does not fire.
 */
interface RuleCapabilityGrants {

    /**
     * Reactive set of granted capability keys. Revocation (#422 PR 3)
     * surfaces here immediately; the fire-time gate reads the persisted
     * store, so a revoked key suppresses the very next fire.
     */
    val grantedKeys: StateFlow<Set<String>>

    /**
     * Publish the capabilities the just-loaded rulesets enable — REPLACING
     * the previous enumeration — and apply the source auto-grant policy:
     * capabilities from a bundled asset source ([ASSET_SOURCE_PREFIX]) are
     * granted unless explicitly denied; any other source (CDN/fork, #192)
     * is never auto-granted and stays pending until the user approves it.
     *
     * Called by the rule loader BEFORE the compiled rules go live, so by the
     * time a frame can classify (and an action can fire) the grant store
     * already reflects the bundle.
     */
    suspend fun reconcile(capabilities: List<RuleCapability>)

    /**
     * Fire-time gate lookup (#417), keyed by the `sourceRuleId` + action that
     * ride the effect — authoritative state, never threaded fields. True iff
     * the pair resolves to at least one enumerated capability AND every
     * enumerated key for the pair is granted (the effect cannot prove which
     * binding definition aimed its target, so all of them must be covered).
     *
     * A null [ruleId] (no provenance) is never granted.
     */
    suspend fun isActionGranted(ruleId: String?, action: RuleAction): Boolean

    companion object {
        /**
         * Source-string prefix marking bundled rule files
         * (`asset:rules/doordash.json`) — the only source the auto-grant
         * policy accepts. SSOT for both the loader (which stamps sources)
         * and the grant policy (which checks them).
         */
        const val ASSET_SOURCE_PREFIX = "asset:"
    }
}
