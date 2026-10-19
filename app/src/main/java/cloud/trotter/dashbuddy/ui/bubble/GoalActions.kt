package cloud.trotter.dashbuddy.ui.bubble

import cloud.trotter.dashbuddy.core.data.settings.AppPreferencesRepository
import cloud.trotter.dashbuddy.feature.bubble.hud.goal.GoalBounds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

/** The preference remains the source of truth; no optimistic goal or ledger event. */
internal class GoalActions(private val scope: CoroutineScope, private val preferences: AppPreferencesRepository) {
    private val writing = MutableStateFlow(false)
    val busy = writing.asStateFlow()
    private val failure = MutableStateFlow(false)
    val failed = failure.asStateFlow()
    private val events = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 1)
    val committed = events.asSharedFlow()

    fun reopen() { failure.value = false }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun committedHandled() { events.resetReplayCache() }

    fun set(dollars: Int, closeEntryId: Long? = null) {
        if (dollars !in GoalBounds.MIN..GoalBounds.MAX) return
        write(dollars.toDouble(), closeEntryId)
    }

    fun clear(closeEntryId: Long? = null) = write(null, closeEntryId)

    private fun write(dollars: Double?, closeEntryId: Long?) {
        if (!writing.compareAndSet(false, true)) return
        failure.value = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) {
                    preferences.setDailyKeptGoalDollars(dollars)
                    closeEntryId?.let { events.emit(it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure.value = true
                Timber.tag("Goal").w("%s", error.javaClass.simpleName)
            } finally { writing.value = false }
        }
    }
}
