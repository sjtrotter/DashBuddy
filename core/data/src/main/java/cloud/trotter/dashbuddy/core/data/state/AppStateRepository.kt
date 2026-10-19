package cloud.trotter.dashbuddy.core.data.state

import cloud.trotter.dashbuddy.core.datastore.appstate.AppStateDataSource
import cloud.trotter.dashbuddy.domain.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import timber.log.Timber

@Singleton
class AppStateRepository @Inject constructor(
    private val dataSource: AppStateDataSource,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val pendingMark = MutableStateFlow<String?>(null)

    /** A session mark survives bubble recreation, including a slow or failed durable write. */
    val goalMetMark: Flow<String?> = combine(dataSource.goalMetMark, pendingMark) { persisted, pending ->
        if (pending != null && persisted == pending) pendingMark.compareAndSet(pending, null)
        pending ?: persisted
    }.distinctUntilChanged()

    fun setGoalMetMark(key: String) {
        if (pendingMark.value == key) return
        pendingMark.value = key
        scope.launch {
            try {
                dataSource.setGoalMetMark(key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Retain pending so a storage failure cannot replay this session's celebration.
                Timber.tag(TAG).w(failure, "Could not persist goal met mark %s", key)
            }
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
