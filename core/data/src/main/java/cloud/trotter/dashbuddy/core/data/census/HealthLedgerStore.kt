package cloud.trotter.dashbuddy.core.data.census

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.data.di.CensusHealthPreferences
import cloud.trotter.dashbuddy.domain.census.CensusUploadStats
import cloud.trotter.dashbuddy.domain.census.HealthKey
import cloud.trotter.dashbuddy.domain.census.HealthLedger
import cloud.trotter.dashbuddy.domain.census.HealthRow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** One backup-excluded JSON value; generation survives clearing the rows. */
@Singleton
class HealthLedgerStore @Inject constructor(
    @param:CensusHealthPreferences private val ds: DataStore<Preferences>,
    private val stats: CensusUploadStats,
) {
    suspend fun load(): HealthLedger = try {
        val json = ds.data.first()[LEDGER_JSON]
        if (json == null) HealthLedger() else decode(json)
    } catch (_: CorruptionException) {
        stats.healthCorrupt.incrementAndGet()
        HealthLedger()
    }

    suspend fun save(ledger: HealthLedger) {
        ds.edit { it[LEDGER_JSON] = Json.encodeToString(ledger) }
    }

    suspend fun clear() {
        ds.edit { preferences ->
            val ledger = preferences[LEDGER_JSON]?.let(::decode) ?: HealthLedger()
            preferences[LEDGER_JSON] = Json.encodeToString(ledger.clear())
        }
    }

    private fun decode(json: String): HealthLedger = try {
        val document = Json.parseToJsonElement(json).jsonObject
        val ledger = Json.decodeFromJsonElement<HealthLedger>(JsonObject(document - "rows"))
        val decoded = buildMap {
            document["rows"]?.jsonObject?.forEach { (key, value) ->
                try {
                    HealthKey.parse(key)
                    put(key, Json.decodeFromJsonElement<HealthRow>(value))
                } catch (_: IllegalArgumentException) {
                    stats.healthCorrupt.incrementAndGet()
                }
            }
        }
        // The persisted document is bounded input too: past MAX_ROWS keep the NEWEST keys (a key
        // starts with its ISO day, so lexical order is chronological) and count the excess as refused.
        val rows = if (decoded.size <= HealthLedger.MAX_ROWS) decoded else {
            stats.healthRowsRefused.addAndGet((decoded.size - HealthLedger.MAX_ROWS).toLong())
            val kept = decoded.keys.sortedDescending().take(HealthLedger.MAX_ROWS).toSet()
            decoded.filterKeys { it in kept }
        }
        ledger.copy(rows = rows)
    } catch (_: IllegalArgumentException) {
        stats.healthCorrupt.incrementAndGet()
        HealthLedger()
    }

    private companion object {
        val LEDGER_JSON = stringPreferencesKey("ledger_json")
    }
}
