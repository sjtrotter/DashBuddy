package cloud.trotter.dashbuddy.core.datastore.appstate

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import cloud.trotter.dashbuddy.core.datastore.di.StateRecoveryPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateRecoveryDataSource @Inject constructor(
    @param:StateRecoveryPreferences private val ds: DataStore<Preferences>
) {
    private object Keys {
        val LAST_KNOWN_STATE = stringPreferencesKey("last_known_state")
        val STATE_TIMESTAMP = longPreferencesKey("state_timestamp")
        val PENDING_OFFER_JSON = stringPreferencesKey("pending_offer_json")
        val ACTIVE_ORDER_JSON = stringPreferencesKey("active_order_json")
    }

    val lastKnownState: Flow<String?> = ds.data.map { it[Keys.LAST_KNOWN_STATE] }
    val stateTimestamp: Flow<Long?> = ds.data.map { it[Keys.STATE_TIMESTAMP] }
    val pendingOfferJson: Flow<String?> = ds.data.map { it[Keys.PENDING_OFFER_JSON] }
    val activeOrderJson: Flow<String?> = ds.data.map { it[Keys.ACTIVE_ORDER_JSON] }

    suspend fun saveState(stateName: String, timestamp: Long) {
        ds.edit { prefs ->
            prefs[Keys.LAST_KNOWN_STATE] = stateName
            prefs[Keys.STATE_TIMESTAMP] = timestamp
        }
    }

    suspend fun savePendingOffer(json: String?) {
        ds.edit {
            if (json == null) it.remove(Keys.PENDING_OFFER_JSON) else it[Keys.PENDING_OFFER_JSON] =
                json
        }
    }

    suspend fun saveActiveOrder(json: String?) {
        ds.edit {
            if (json == null) it.remove(Keys.ACTIVE_ORDER_JSON) else it[Keys.ACTIVE_ORDER_JSON] =
                json
        }
    }

    suspend fun clear() = ds.edit { it.clear() }
}