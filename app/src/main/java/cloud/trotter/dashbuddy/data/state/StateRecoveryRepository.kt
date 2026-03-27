package cloud.trotter.dashbuddy.data.state

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
        val json = dataSource.getJson() ?: return null
        val timestamp = dataSource.getTimestamp()

        if (System.currentTimeMillis() - timestamp > expiryMs) {
            clearState() // Cleanup old data
            return null
        }
        return json
    }

    suspend fun clearState() {
        dataSource.clearState()
    }
}