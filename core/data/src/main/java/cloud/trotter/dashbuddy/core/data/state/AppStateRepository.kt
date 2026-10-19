package cloud.trotter.dashbuddy.core.data.state

import cloud.trotter.dashbuddy.core.datastore.appstate.AppStateDataSource
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class AppStateRepository @Inject constructor(
    private val dataSource: AppStateDataSource,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val requested = MutableStateFlow<String?>(null)

    /**
     * A read prefers this process's latest request because persistence only matters across processes.
     * A superseded write is cancelled by collectLatest and the newest key is always the last to land.
     */
    val goalMetMark: Flow<String?> = combine(dataSource.goalMetMark, requested) { persisted, request ->
        request ?: persisted
    }

    init {
        scope.launch {
            requested.filterNotNull().collectLatest { write(it) }
        }
    }

    fun setGoalMetMark(key: String) {
        requested.value = key
    }

    private suspend fun write(key: String) {
        try {
            dataSource.setGoalMetMark(key)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            Timber.tag(TAG).w(failure, "Could not persist goal met mark")
        }
    }

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
