package cloud.trotter.log.gig.core.datastore.census

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.log.gig.core.datastore.capability.ConsentReceiptJson
import cloud.trotter.log.gig.core.datastore.di.ContributorPreferences
import cloud.trotter.log.gig.domain.census.contributor.ContributorRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Singleton

/**
 * #1625: the contributor record, one JSON value in its own store (excluded from backup and transfer: a grant belongs
 * to this phone's census ID). An unreadable record reads as the empty one, which holds no grant (fail closed).
 */
@Singleton
class ContributorDataSource @Inject constructor(
    @param:ContributorPreferences private val ds: DataStore<Preferences>,
) {
    private val key = stringPreferencesKey("contributor_record_json")

    val record: Flow<ContributorRecord> = ds.data.map { prefs -> decode(prefs[key]) }

    /** Applies [transform] inside one edit, so two changes never overwrite each other. Returns the stored record. */
    suspend fun update(transform: (ContributorRecord) -> ContributorRecord): ContributorRecord {
        var result = ContributorRecord()
        ds.edit { prefs ->
            result = transform(decode(prefs[key]))
            prefs[key] = ConsentReceiptJson.encodeToString(result)
        }
        return result
    }

    private fun decode(encoded: String?): ContributorRecord = encoded?.let {
        runCatching { ConsentReceiptJson.decodeFromString<ContributorRecord>(it) }.getOrNull()
    } ?: ContributorRecord()
}
