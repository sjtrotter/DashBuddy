package cloud.trotter.dashbuddy.core.data.capability

import cloud.trotter.dashbuddy.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * THE consent SSOT for rule-enabled actions (#417/#422): joins the
 * capabilities the currently loaded rulesets enumerate (in-memory, replaced
 * wholesale on every [reconcile]) with the persisted grant set
 * ([RuleCapabilityDataSource]).
 *
 * Grants are NOT pruned when a key drops out of the enumeration: keys are
 * content-pinned, so a stale grant can never cover a changed binding, and
 * keeping it means a temporary ruleset swap doesn't cost the user a
 * re-consent when the original comes back. (Replacement-survival semantics
 * for remote bundles are #419's scope.)
 */
@Singleton
class RuleCapabilityRepository @Inject constructor(
    private val dataSource: RuleCapabilityDataSource,
    @ApplicationScope scope: CoroutineScope,
) : RuleCapabilityGrants {

    /**
     * Capabilities enabled by the CURRENTLY loaded rulesets, keyed by
     * (ruleId, action). Set by [reconcile] before the loader swaps the
     * compiled rules live, so the gate never sees rules without their
     * enumeration.
     */
    private val enumerated =
        MutableStateFlow<Map<Pair<String, RuleAction>, List<RuleCapability>>>(emptyMap())

    override val grantedKeys: StateFlow<Set<String>> = dataSource.granted
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override suspend fun reconcile(capabilities: List<RuleCapability>) {
        enumerated.value = capabilities.groupBy { it.ruleId to it.action }
        dataSource.update { granted, denied ->
            (granted + autoGrantSelection(capabilities, denied)) to denied
        }
        Timber.i(
            "RuleCapabilityRepository: reconciled %d capabilit(ies) from rule load",
            capabilities.size,
        )
    }

    override suspend fun isActionGranted(ruleId: String?, action: RuleAction): Boolean {
        if (ruleId == null) return false // no provenance → not consentable
        val keys = enumerated.value[ruleId to action]?.map { it.key }
        val granted = try {
            // Authoritative read at fire time — the persisted store, not a
            // cached copy, so a revocation suppresses the very next fire.
            dataSource.granted.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Grant read failed — denying %s for '%s' (fail closed)", action.wire, ruleId)
            return false
        }
        return actionKeysCovered(keys, granted)
    }
}

/**
 * Auto-grant policy (#417/#422): bundled (asset-source) capabilities are
 * granted on load so the single-user alpha has zero consent friction; any
 * other source (CDN/fork — #192) is NEVER auto-granted and stays pending
 * until the user approves it. An explicitly denied key is excluded — a
 * revocation must not be silently undone by the next rule load.
 *
 * Pure and top-level so policy is testable without Android.
 */
fun autoGrantSelection(capabilities: List<RuleCapability>, denied: Set<String>): Set<String> =
    capabilities.asSequence()
        .filter { it.source.startsWith(RuleCapabilityGrants.ASSET_SOURCE_PREFIX) }
        .map { it.key }
        .filterNot { it in denied }
        .toSet()

/**
 * ALL-keys-granted semantics (#417): when one (rule, action) pair enumerates
 * to several capability keys (rule-level and branch-level bindings with
 * different predicates), the fire-time effect cannot prove which definition
 * aimed its target — so every key must be granted. Unknown pair or empty
 * enumeration → false (fail closed).
 */
fun actionKeysCovered(enumeratedKeys: List<String>?, granted: Set<String>): Boolean =
    !enumeratedKeys.isNullOrEmpty() && enumeratedKeys.all { it in granted }
