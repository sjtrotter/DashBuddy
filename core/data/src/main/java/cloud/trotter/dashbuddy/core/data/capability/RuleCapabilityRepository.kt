package cloud.trotter.dashbuddy.core.data.capability

import cloud.trotter.dashbuddy.core.datastore.capability.GrantSnapshot
import cloud.trotter.dashbuddy.core.datastore.capability.RuleCapabilityDataSource
import cloud.trotter.dashbuddy.domain.action.RuleAction
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import cloud.trotter.dashbuddy.domain.capability.PrivacyDisclosure
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber

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
    @param:Named("appVersionName") private val appVersion: String,
) : RuleCapabilityGrants {

    /**
     * Capabilities enabled by the CURRENTLY loaded rulesets — the SSOT for both
     * the fire-time gate ([isActionGranted] filters it by (ruleId, action)) and
     * the consent surface ([capabilities] exposes it). Set by [reconcile] before
     * the loader swaps the compiled rules live, so the gate never sees rules
     * without their enumeration.
     */
    private val enumerated = MutableStateFlow<List<RuleCapability>>(emptyList())

    override val capabilities: StateFlow<List<RuleCapability>> = enumerated

    override val grantedKeys: StateFlow<Set<String>> = dataSource.granted
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val deniedKeys: StateFlow<Set<String>> = dataSource.denied
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    override val receipts: StateFlow<Map<String, ConsentReceipt>> = dataSource.receipts
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override suspend fun reconcile(capabilities: List<RuleCapability>) {
        // Publish the enumeration ONLY — grants NOTHING (#843). Bundled and
        // remote sources are enumerated identically; new capability keys stay
        // undecided until the user answers the consent prompt. The
        // fire-time gate stays fail-closed for anything not in the granted set,
        // so leaving the persisted store untouched here is the whole point.
        enumerated.value = capabilities
        // #1167 review: the counts are diagnostics; their read must not become a reconcile failure
        try {
            val keys = capabilities.map { it.key }.toSet()
            val (granted, denied) = dataSource.snapshot()
            val grantedCount = keys.count { it in granted }
            val deniedCount = keys.count { it in denied }
            Timber.tag(TAG).i(
                "reconciled %d capabilit(ies) from rule load: granted=%d denied=%d undecided=%d (stale grants=%d)",
                keys.size,
                grantedCount,
                deniedCount,
                keys.size - grantedCount - deniedCount,
                granted.count { it !in keys },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "reconciled %d capabilit(ies); consent counts unavailable", capabilities.size)
        }
    }

    /**
     * One-shot consent-schema migration (#843/#1167), delegated to the persisted
     * store. The v2 upgrade clears grants and denials whose old key shape cannot
     * map to one decision per (rule, action), then stamps the version marker so it
     * runs once. Called at app startup BEFORE the rulesets go live, so no
     * automation can fire against a stale grant. See
     * [RuleCapabilityDataSource.migrateConsentSchemaIfNeeded].
     */
    suspend fun migrateConsentSchemaIfNeeded() {
        // Snapshot BEFORE the migration so the INFO line reflects reality: a fresh
        // install stamps the marker (ran == true) but had nothing to clear, and a
        // misleading "cleared stored grants and denials" would then pollute the
        // shareable log every first launch. Only log when grants or denials were cleared.
        val (granted, denied) = dataSource.snapshot()
        val hadState = granted.isNotEmpty() || denied.isNotEmpty()
        if (dataSource.migrateConsentSchemaIfNeeded() && hadState) {
            // INFO milestone — PII-safe (no keys, just a count-free status).
            Timber.tag(TAG).i(
                "consent-schema migration ran: cleared stored grants and denials (key shape v2, #1167); " +
                    "automation stays off until re-consented",
            )
        }
    }

    override suspend fun setGranted(key: String, granted: Boolean) {
        dataSource.update { grantedKeys, deniedKeys, receipts ->
            val (newGranted, newDenied) = applyGrantChange(key, granted, grantedKeys, deniedKeys)
            GrantSnapshot(
                newGranted,
                newDenied,
                receipts + (key to ConsentReceipt(
                    System.currentTimeMillis(), appVersion, PrivacyDisclosure.REVISION, granted,
                )),
            )
        }
        // INFO milestone — a consent change is user-meaningful and PII-safe (the
        // key is a sha256 hash, no store/customer text).
        Timber.tag(TAG).i("consent %s for capability key %s", if (granted) "granted" else "revoked", key)
    }

    override suspend fun isActionGranted(ruleId: String?, action: RuleAction): Boolean {
        if (ruleId == null) return false // no provenance → not consentable
        val keys = enumerated.value
            .filter { it.ruleId == ruleId && it.action == action }
            .map { it.key }
        val granted = try {
            // Authoritative read at fire time — the persisted store, not a
            // cached copy, so a revocation suppresses the very next fire.
            dataSource.granted.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Grant read failed — denying %s for '%s' (fail closed)", action.wire, ruleId)
            return false
        }
        return actionKeysCovered(keys, granted)
    }

    private companion object {
        const val TAG = "Consent"
    }
}

/**
 * Consent grant/revoke set transform (#422 PR 3 / #843), pure and top-level so
 * it is testable without DataStore. Granting a [key] adds it to [granted] and
 * clears any prior denial; **denying persists an explicit denial** so the
 * capability leaves "undecided" and the consent prompt never re-asks (a denial
 * is durable, not a deferral). Since #843 killed auto-grant, granting here is
 * the ONLY way a key enters [granted]. Returns the new (granted, denied) pair
 * for one atomic [RuleCapabilityDataSource.update].
 */
fun applyGrantChange(
    key: String,
    grant: Boolean,
    granted: Set<String>,
    denied: Set<String>,
): Pair<Set<String>, Set<String>> =
    if (grant) (granted + key) to (denied - key)
    else (granted - key) to (denied + key)

/**
 * ALL-keys-granted semantics (#417): when one (rule, action) pair enumerates
 * to several capability keys (rule-level and branch-level bindings with
 * different predicates), the fire-time effect cannot prove which definition
 * aimed its target — so every key must be granted. Unknown pair or empty
 * enumeration → false (fail closed).
 */
fun actionKeysCovered(enumeratedKeys: List<String>?, granted: Set<String>): Boolean =
    !enumeratedKeys.isNullOrEmpty() && enumeratedKeys.all { it in granted }
