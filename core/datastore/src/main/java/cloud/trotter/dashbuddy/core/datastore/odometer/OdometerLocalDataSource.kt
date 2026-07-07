package cloud.trotter.dashbuddy.core.datastore.odometer

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.OdometerPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OdometerLocalDataSource @Inject constructor(
    @param:OdometerPreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        val TOTAL_METERS = doublePreferencesKey("total_meters")

        /**
         * #438 B5: the id of the session whose miles the no-arg [OdometerRepository.sessionMilesFlow]
         * (the HUD's "session miles") tracks, persisted so a crash recovery restores the right anchor.
         */
        val CURRENT_SESSION_ID = stringPreferencesKey("current_session_id")

        /**
         * #438 B5: per-session anchor keys — the odometer total at each session's start. Replaces the
         * single global `session_anchor`, so a SECOND concurrent session gets its OWN anchor and can't
         * zero the first's accrued miles.
         */
        fun sessionAnchor(sessionId: String) = doublePreferencesKey("session_anchor_$sessionId")
    }

    val totalMetersFlow: Flow<Double> = ds.data.map { it[Keys.TOTAL_METERS] ?: 0.0 }
    val currentSessionIdFlow: Flow<String?> = ds.data.map { it[Keys.CURRENT_SESSION_ID] }

    fun sessionAnchorFlow(sessionId: String): Flow<Double?> =
        ds.data.map { it[Keys.sessionAnchor(sessionId)] }

    suspend fun saveTotalMeters(meters: Double) {
        ds.edit { it[Keys.TOTAL_METERS] = meters }
    }

    /**
     * Persist [sessionId]'s anchor and mark it the current session in one edit. The old single
     * `session_anchor` (double) key is intentionally left orphaned — dev is the only user, and a
     * mid-session upgrade merely resets that one session's HUD miles to 0 until re-anchored (alpha).
     */
    suspend fun saveSessionAnchor(sessionId: String, anchor: Double) {
        ds.edit {
            it[Keys.sessionAnchor(sessionId)] = anchor
            it[Keys.CURRENT_SESSION_ID] = sessionId
        }
    }

    suspend fun clear() {
        ds.edit { it.clear() }
    }
}
