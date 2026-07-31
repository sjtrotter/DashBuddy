package cloud.trotter.dashbuddy.core.datastore.plan

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.WeeklyPlanPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for the driver's saved weekly plans (#981 / brief §7.7).
 *
 * **Why a DataStore and not a Room table.** A saved plan is a **user artifact**, not an observation:
 * nothing in `app_events` implies it, and nothing can rebuild it. The analytics tables are explicitly
 * a rebuildable projection that a `PROJECTOR_VERSION` bump WIPES (CLAUDE.md §5) — putting an
 * unrebuildable artifact in there would mean a routine projector bump silently deletes the driver's
 * plan and the grade it was owed. The alternative cost is also real: a Room table for one small
 * per-week record buys a schema-version bump, an `AutoMigration`, an exported-schema regeneration and
 * a `MigrationTestHelper` case, for data with no query surface beyond "give me the current one".
 *
 * So it lives in its own single-concern preferences store — the eighth, alongside app prefs, app
 * state, dev settings, odometer, strategy, platforms and rule-capability grants — holding ONE
 * JSON string encoded by the `:domain` `WeeklyPlanCodec` (the `LegState` precedent: the codec is pure
 * and unit-tested, the store only moves the string).
 *
 * This layer deliberately knows nothing about the plan's shape; decoding (and its fail-closed
 * behaviour) belongs to the codec, and the repository above is where the two meet.
 */
@Singleton
class WeeklyPlanDataSource @Inject constructor(
    @param:WeeklyPlanPreferences private val ds: DataStore<Preferences>,
) {
    private object Keys {
        /** The encoded saved-plan list. Do not rename — a saved plan is keyed on it. */
        val SAVED_PLANS = stringPreferencesKey("saved_plans_json")
    }

    /** The raw encoded blob, or null when the driver has never saved a plan. */
    val savedPlansJson: Flow<String?> = ds.data.map { it[Keys.SAVED_PLANS] }

    suspend fun savePlansJson(json: String) {
        ds.edit { it[Keys.SAVED_PLANS] = json }
    }

    suspend fun clear() {
        ds.edit { it.remove(Keys.SAVED_PLANS) }
    }
}
