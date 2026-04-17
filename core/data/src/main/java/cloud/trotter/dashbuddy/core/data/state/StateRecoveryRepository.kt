package cloud.trotter.dashbuddy.core.data.state

import cloud.trotter.dashbuddy.core.datastore.appstate.StateRecoveryDataSource
import kotlinx.coroutines.flow.firstOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StateRecoveryRepository @Inject constructor(
    private val dataSource: StateRecoveryDataSource
) {
    private val expiryMs = 30 * 60 * 1000L // 30 Minutes

    suspend fun saveState(json: String) {
        dataSource.saveState(json, System.currentTimeMillis())
    }

    suspend fun getFreshState(): String? {
        // DataStore uses Flows, so we use firstOrNull() for a one-shot read!
        val json = dataSource.lastKnownState.firstOrNull() ?: return null
        val timestamp = dataSource.stateTimestamp.firstOrNull() ?: 0L

        if (System.currentTimeMillis() - timestamp > expiryMs) {
            clearState() // Cleanup old data
            return null
        }
        return json
    }

    suspend fun clearState() {
        // Using the clear() method we generated in the DataSource
        dataSource.clear()
    }
}