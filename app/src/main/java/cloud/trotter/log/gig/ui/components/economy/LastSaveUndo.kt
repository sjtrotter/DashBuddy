package cloud.trotter.log.gig.ui.components.economy

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import cloud.trotter.log.gig.ui.main.settings.garage.WriteSubmission
import androidx.compose.ui.res.stringResource
import cloud.trotter.log.gig.R
import cloud.trotter.log.gig.ui.main.settings.garage.VehicleCostWriter
import cloud.trotter.log.gig.ui.main.settings.garage.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One field's raw prior state. The restore closes over only that field, never a profile. */
class LastSaveUndo private constructor(
    val key: String,
    @param:StringRes val labelRes: Int,
    val priorState: Any?,
    internal val restore: suspend (() -> Boolean) -> Boolean,
) {
    companion object {
        fun <T> captured(key: String, @StringRes labelRes: Int, previous: T,
            restore: suspend (T) -> Boolean) = LastSaveUndo(key, labelRes, previous) { restore(previous) }

        fun <T> checked(key: String, @StringRes labelRes: Int, previous: T,
            restore: suspend (T, () -> Boolean) -> Boolean) =
            LastSaveUndo(key, labelRes, previous) { current -> restore(previous, current) }
    }
}

/** Uses the host's writer for both saves and restores; screen lifetime invalidates queued offers. */
class LastSaveUndoState(private val writer: VehicleCostWriter, private val edits: EditSession) {
    private val _lastSave = MutableStateFlow<LastSaveUndo?>(null)
    val lastSave = _lastSave.asStateFlow()
    @Volatile private var epoch = 0
    private var offering = true

    fun write(action: suspend () -> LastSaveUndo?): Deferred<WriteResult> {
        invalidate()
        val acceptedEpoch = epoch
        val offer = offering
        return writer.write {
            val saved = action()
            if (saved != null && offer && offering && acceptedEpoch == epoch) _lastSave.value = saved
            saved != null
        }
    }

    fun undo(expected: LastSaveUndo): Deferred<WriteResult> {
        if (_lastSave.value !== expected) return CompletableDeferred(WriteResult.REJECTED)
        _lastSave.value = null
        val acceptedEpoch = epoch
        edits.discard(expected.key)
        return writer.writeResult {
            if (acceptedEpoch != epoch || !expected.restore { acceptedEpoch == epoch }) WriteResult.CHANGED else WriteResult.SAVED
        }
    }

    fun dismiss(expected: LastSaveUndo) { if (_lastSave.value === expected) _lastSave.value = null }
    fun invalidate() { epoch++; _lastSave.value = null }
    fun clear() { invalidate(); offering = false }
    fun resume() { offering = true }
}

@Composable
fun LastSaveUndoSnackbar(save: LastSaveUndo?, host: SnackbarHostState,
    onUndo: (LastSaveUndo) -> Deferred<WriteResult>, onDismiss: (LastSaveUndo) -> Unit) {
    val message = save?.let { stringResource(R.string.economy_field_saved, stringResource(it.labelRes)) }
    val action = stringResource(R.string.economy_field_undo)
    val scope = rememberCoroutineScope()
    LaunchedEffect(save, host) {
        if (save != null && message != null) {
            when (host.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Short)) {
                SnackbarResult.ActionPerformed -> {
                    val submission = onUndo(save)
                    scope.launch {
                        showUndoResult(submission, host)
                    }
                }
                SnackbarResult.Dismissed -> onDismiss(save)
            }
        }
    }
}

internal suspend fun showUndoResult(submission: Deferred<WriteResult>, host: SnackbarHostState) {
    val result = (submission as? WriteSubmission)?.completion?.await() ?: submission.await()
    if (result == WriteResult.CHANGED) host.showSnackbar("Couldn't undo. The value changed.")
}
