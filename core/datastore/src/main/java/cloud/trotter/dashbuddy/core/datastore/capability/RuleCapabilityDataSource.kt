package cloud.trotter.dashbuddy.core.datastore.capability

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
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
 * are stored separately so a future revocation (consent UI, #422 PR 3) is
 * not silently undone by the next load's auto-grant pass.
 */
@Singleton
class RuleCapabilityDataSource @Inject constructor(
    @param:RuleCapabilityPreferences private val ds: DataStore<Preferences>,
) {
    private object Keys {
        val GRANTED = stringSetPreferencesKey("granted_capability_keys")
        val DENIED = stringSetPreferencesKey("denied_capability_keys")
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
}
