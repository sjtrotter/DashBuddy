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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        Json.decodeFromString<HealthLedger>(json).also { ledger -> ledger.rows.keys.forEach { HealthKey.parse(it) } }
    } catch (_: IllegalArgumentException) {
        stats.healthCorrupt.incrementAndGet()
        HealthLedger()
    }

    private companion object {
        val LEDGER_JSON = stringPreferencesKey("ledger_json")
    }
}
