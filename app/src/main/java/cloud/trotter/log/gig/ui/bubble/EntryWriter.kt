package cloud.trotter.log.gig.ui.bubble

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

/** Serializes durable entry writes and retains one completion until its UI handles it. */
internal class EntryWriter<T>(
    private val scope: CoroutineScope,
    private val tag: String,
    private val onFailure: (Exception) -> Unit,
) {
    private val writing = MutableStateFlow(false)
    val busy = writing.asStateFlow()
    private val events = MutableSharedFlow<T>(replay = 1, extraBufferCapacity = 1)
    val committed = events.asSharedFlow()

    suspend fun commit(value: T) { events.emit(value) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun committedHandled() { events.resetReplayCache() }

    fun write(reportFailure: Boolean = true, onFailure: (Exception) -> Unit = {}, block: suspend () -> Unit) {
        if (!writing.compareAndSet(false, true)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(NonCancellable) { block() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (reportFailure) this@EntryWriter.onFailure(error)
                onFailure(error)
                Timber.tag(tag).w("%s", error.javaClass.simpleName)
            } finally { writing.value = false }
        }
    }
}
