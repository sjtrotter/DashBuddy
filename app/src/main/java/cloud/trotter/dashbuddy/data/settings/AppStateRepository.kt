package cloud.trotter.dashbuddy.data.settings

import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppStateRepository @Inject constructor(
    private val dataSource: AppStateDataSource
) {
    val isFirstRun: Flow<Boolean> = dataSource.isFirstRun

    suspend fun setFirstRunComplete() {
        dataSource.update { it[AppStateDataSource.Keys.IS_FIRST_RUN] = false }
    }

    suspend fun clearPreferences() {
        Timber.w("Clearing App State Preferences")
        dataSource.clear()
    }
}