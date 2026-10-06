package cloud.trotter.dashbuddy.core.datastore.capability

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.RuleCapabilityPreferences
import cloud.trotter.dashbuddy.domain.capability.ConsentReceipt
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString

data class GrantSnapshot(
    val granted: Set<String>,
    val denied: Set<String>,
    val receipts: Map<String, ConsentReceipt>,
)

/**
 * Persistence for rule-capability consent (#417/#422): the set of granted
 * capability keys (`RuleCapability.key` — content-pinned sha256 hashes) and
 * the set the user has explicitly denied.
 *
 * Grants are keyed by content, not rule id, so they survive reloads of an
 * unchanged ruleset and *stop covering* a rule whose binding definition
 * changed — that re-consent property is the point of the key (#422). Denials
 * are stored separately so an explicit opt-out (consent prompt/settings, #843)
 * is durable across unchanged ruleset reloads. A key-shape migration
 * ([migrateConsentSchemaIfNeeded]) clears both sets for fresh consent (#1167).
 */
@Singleton
class RuleCapabilityDataSource @Inject constructor(
    @param:RuleCapabilityPreferences private val ds: DataStore<Preferences>,
) {
    private object Keys {
        val GRANTED = stringSetPreferencesKey("granted_capability_keys")
        val DENIED = stringSetPreferencesKey("denied_capability_keys")
        val RECEIPTS = stringPreferencesKey("consent_receipts_json")

        /**
         * The consent-schema version last applied on this device (#843). Absent
         * (⇒ 0) on a store written before the no-auto-grant migration existed —
         * i.e. one that may hold auto-granted keys that were never an explicit
         * user act. [migrateConsentSchemaIfNeeded] stamps [CONSENT_SCHEMA_VERSION]
         * here so the one-shot clear runs exactly once.
         */
        val SCHEMA_VERSION = intPreferencesKey("consent_schema_version")
    }

    /** Granted capability keys; the fire-time gate reads this (fail closed when absent). */
    val granted: Flow<Set<String>> = ds.data.map { it[Keys.GRANTED] ?: emptySet() }

    /** Explicitly denied capability keys; auto-grant never overrides these. */
    val denied: Flow<Set<String>> = ds.data.map { it[Keys.DENIED] ?: emptySet() }

    /** A corrupt record never changes grant enforcement. */
    val receipts: Flow<Map<String, ConsentReceipt>> = ds.data.map { decodeReceipts(it) }

    private fun decodeReceipts(prefs: Preferences): Map<String, ConsentReceipt> =
        prefs[Keys.RECEIPTS]?.let { encoded ->
            runCatching { ConsentReceiptJson.decodeFromString<Map<String, ConsentReceipt>>(encoded) }.getOrDefault(emptyMap())
        } ?: emptyMap()

    /** Both decisions from one Preferences emission, so concurrent edits cannot split the read. */
    suspend fun snapshot(): GrantSnapshot {
        val prefs = ds.data.first()
        return GrantSnapshot(
            granted = prefs[Keys.GRANTED] ?: emptySet(),
            denied = prefs[Keys.DENIED] ?: emptySet(),
            receipts = decodeReceipts(prefs),
        )
    }

    /**
     * Atomically transform both sets and their receipts in ONE DataStore edit (#364 lesson:
     * read-modify-write must happen inside the edit so concurrent updates
     * can't interleave). [transform] receives the currently-saved sets and
     * returns the new (granted, denied, receipts) snapshot.
     */
    suspend fun update(
        transform: (granted: Set<String>, denied: Set<String>, receipts: Map<String, ConsentReceipt>) -> GrantSnapshot,
    ) {
        ds.edit { prefs ->
            val (newGranted, newDenied, newReceipts) = transform(
                prefs[Keys.GRANTED] ?: emptySet(),
                prefs[Keys.DENIED] ?: emptySet(),
                decodeReceipts(prefs),
            )
            prefs[Keys.GRANTED] = newGranted
            prefs[Keys.DENIED] = newDenied
            prefs[Keys.RECEIPTS] = ConsentReceiptJson.encodeToString(newReceipts)
        }
    }

    /**
     * v1 (#843) cleared pre-consent auto-grants and kept denials; v2 (#1167) changes the key SHAPE
     * (one key per (rule, action)), so neither old grants nor old denials can be mapped — both are
     * cleared and every capability lands undecided for the prompt to re-collect (fail-closed).
     * Stamps the version in the same atomic edit; returns true iff migration ran, and subsequent
     * calls preserve fresh decisions.
     * A future v3 that wants to KEEP denials must add an explicit per-version step here, with a test.
     */
    suspend fun migrateConsentSchemaIfNeeded(): Boolean {
        var migrated = false
        ds.edit { prefs ->
            val stored = prefs[Keys.SCHEMA_VERSION] ?: 0
            if (stored < CONSENT_SCHEMA_VERSION) {
                prefs[Keys.GRANTED] = emptySet()
                prefs[Keys.DENIED] = emptySet()
                prefs.remove(Keys.RECEIPTS)
                prefs[Keys.SCHEMA_VERSION] = CONSENT_SCHEMA_VERSION
                migrated = true
            }
        }
        return migrated
    }

    companion object {
        /**
         * Current consent-schema version. v2 (#1167) changes the key shape to one
         * key per (rule, action), clearing old grants and denials for fresh consent.
         */
        const val CONSENT_SCHEMA_VERSION = 2
    }
}
