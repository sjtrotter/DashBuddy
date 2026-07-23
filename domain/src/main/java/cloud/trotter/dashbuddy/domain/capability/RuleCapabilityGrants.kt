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
     * Reactive set of granted capability keys — the ONLY keys the fire-time
     * gate ([isActionGranted]) treats as fireable. A key is here iff the user
     * explicitly opted in (there is no auto-grant, #843). A grant/revoke
     * surfaces here immediately; the fire-time gate reads the persisted
     * store, so a revoked key suppresses the very next fire.
     */
    val grantedKeys: StateFlow<Set<String>>

    /**
     * Reactive set of explicitly DENIED capability keys — the user opted out.
     * A denial persists across rule loads and never re-prompts (#843). Exposed
     * so the consent PROMPT (#843) can derive the *undecided* set
     * (`capabilities − granted − denied`): a capability is undecided when it is
     * enumerated but appears in neither this set nor [grantedKeys]. Symmetric
     * read projection of the same persisted store as [grantedKeys]; never a
     * gate (an undecided key already fails [isActionGranted] by absence from the
     * granted set — denial vs undecided differ only in whether we re-prompt).
     */
    val deniedKeys: StateFlow<Set<String>>

    /**
     * The capabilities the CURRENTLY loaded rulesets enumerate — the same list
     * the last [reconcile] published, replaced wholesale on each rule load. The
     * consent surface (#422 PR 3) joins this with [grantedKeys] to render one
     * chip per capability with its provenance ([RuleCapability.source]) and grant
     * state. Read-only projection of the enumeration the gate already holds — the
     * UI is a consent *record*, never a second enforcement point.
     */
    val capabilities: StateFlow<List<RuleCapability>>

    /**
     * Grant ([granted] = true) or revoke/deny ([granted] = false) one
     * capability [key] — the consent decision, written from the prompt (#843)
     * or the settings record (#422 PR 3). This is the ONLY way a grant comes
     * to exist. Both sides write atomically: granting adds [key] to the granted
     * set and clears any prior denial; **denying persists an explicit denial in
     * [deniedKeys]** so the capability moves out of "undecided" and the prompt
     * never re-asks (a denial is durable, not a deferral). A revocation
     * surfaces on [grantedKeys] immediately, so the fail-closed fire-time gate
     * ([isActionGranted]) suppresses the very next automation tap.
     */
    suspend fun setGranted(key: String, granted: Boolean)

    /**
     * Publish the capabilities the just-loaded rulesets enable — REPLACING the
     * previous enumeration. **Grants NOTHING, from any source (#843):** a
     * bundled (asset) source is enumerated exactly like a future remote source,
     * landing every capability as UNDECIDED until the user opts in through the
     * consent prompt. Per Google Play policy the user must consent to EACH
     * automation individually; no capability arrives pre-granted, so [source]
     * is recorded for provenance/display only, never as a grant trigger.
     *
     * Called by the rule loader BEFORE the compiled rules go live, so the
     * consent surface and the fire-time gate see the current enumeration; the
     * gate stays fail-closed for anything not in [grantedKeys].
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
         * (`asset:rules/doordash.json`), covered by the APK signature. Since
         * #843 killed auto-grant this no longer gates any grant — bundled and
         * remote sources are enumerated identically and both land undecided —
         * it only lets the consent surface label a source's provenance
         * ("built-in" vs a future downloaded source). SSOT for both the loader
         * (which stamps sources) and that display distinction.
         */
        const val ASSET_SOURCE_PREFIX = "asset:"
    }
}
