package cloud.trotter.dashbuddy.core.datastore.capability

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.RuleCapabilityPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for rule-capability consent (#417/#422): the set of granted
 * capability keys (`RuleCapability.key` — content-pinned sha256 hashes) and
 * the set the user has explicitly denied.
 *
 * Grants are keyed by content, not rule id, so they survive reloads of an
 * unchanged ruleset and *stop covering* a rule whose binding definition
 * changed — that re-consent property is the point of the key (#422). Denials
 * are stored separately so an explicit opt-out (consent prompt/settings, #843)
 * is durable — reloads never re-ask, and the one-shot schema migration
 * ([migrateConsentSchemaIfNeeded]) preserves denials while clearing grants.
 */
@Singleton
class RuleCapabilityDataSource @Inject constructor(
    @param:RuleCapabilityPreferences private val ds: DataStore<Preferences>,
) {
    private object Keys {
        val GRANTED = stringSetPreferencesKey("granted_capability_keys")
        val DENIED = stringSetPreferencesKey("denied_capability_keys")

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

    /**
     * Atomically transform both sets in ONE DataStore edit (#364 lesson:
     * read-modify-write must happen inside the edit so concurrent updates
     * can't interleave). [transform] receives the currently-saved sets and
     * returns the new (granted, denied) pair.
     */
    suspend fun update(
        transform: (granted: Set<String>, denied: Set<String>) -> Pair<Set<String>, Set<String>>,
    ) {
        ds.edit { prefs ->
            val (newGranted, newDenied) = transform(
                prefs[Keys.GRANTED] ?: emptySet(),
                prefs[Keys.DENIED] ?: emptySet(),
            )
            prefs[Keys.GRANTED] = newGranted
            prefs[Keys.DENIED] = newDenied
        }
    }

    /**
     * One-shot consent-schema migration (#843). On the first run whose stored
     * [Keys.SCHEMA_VERSION] is below [CONSENT_SCHEMA_VERSION], **clear the
     * granted set** — the old auto-grant policy could have populated it without
     * an explicit user act, so every capability must return to undecided and be
     * re-consented through the prompt — while **keeping the denied set** (an
     * explicit opt-out stays honored) — then stamp the version so the clear
     * never runs again. Idempotent: a second call finds the version already at
     * target and no-ops. All in one atomic edit.
     *
     * Returns true iff the clear ran (for the caller's INFO log), false on a
     * no-op. Fail-closed by construction: clearing grants can only *remove*
     * automation, never fabricate it.
     */
    suspend fun migrateConsentSchemaIfNeeded(): Boolean {
        var migrated = false
        ds.edit { prefs ->
            val stored = prefs[Keys.SCHEMA_VERSION] ?: 0
            if (stored < CONSENT_SCHEMA_VERSION) {
                prefs[Keys.GRANTED] = emptySet()
                // Keys.DENIED intentionally preserved.
                prefs[Keys.SCHEMA_VERSION] = CONSENT_SCHEMA_VERSION
                migrated = true
            }
        }
        return migrated
    }

    companion object {
        /**
         * Current consent-schema version. Bumped from 0 to 1 by #843 (kill the
         * auto-grant): the bump flips every previously auto-granted capability
         * back to undecided so the prompt collects fresh, explicit consent.
         */
        const val CONSENT_SCHEMA_VERSION = 1
    }
}
