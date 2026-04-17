package cloud.trotter.dashbuddy.core.data.state

import cloud.trotter.dashbuddy.core.datastore.appstate.AppStateDataSource
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
        dataSource.setFirstRunComplete()
    }

    suspend fun clearPreferences() {
        Timber.Forest.w("Clearing App State Preferences")
        dataSource.clear()
    }
}