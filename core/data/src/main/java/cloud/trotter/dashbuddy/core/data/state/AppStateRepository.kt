package cloud.trotter.dashbuddy.core.data.state

import cloud.trotter.dashbuddy.core.datastore.appstate.AppStateDataSource
import cloud.trotter.dashbuddy.domain.state.GoalMetMarkStore
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStateRepository @Inject constructor(
    private val dataSource: AppStateDataSource
) : GoalMetMarkStore {
    override val goalMetMark: Flow<String?> = dataSource.goalMetMark

    override suspend fun setGoalMetMark(key: String) = dataSource.setGoalMetMark(key)

    val isFirstRun: Flow<Boolean> = dataSource.isFirstRun

    /** False until the #938 English-locale boundary notice has been posted once. */
    val localeBoundaryNoticeShown: Flow<Boolean> = dataSource.localeBoundaryNoticeShown

    suspend fun setFirstRunComplete() {
        dataSource.setFirstRunComplete()
    }

    suspend fun setLocaleBoundaryNoticeShown() {
        dataSource.setLocaleBoundaryNoticeShown()
    }

    suspend fun clearPreferences() {
        Timber.tag(TAG).w("Clearing App State Preferences")
        dataSource.clear()
    }

    private companion object {
        private const val TAG = "AppState"
    }
}