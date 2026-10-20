package cloud.trotter.dashbuddy.ui.components.economy

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import cloud.trotter.dashbuddy.R
import cloud.trotter.dashbuddy.ui.main.settings.garage.VehicleCostWriter
import cloud.trotter.dashbuddy.ui.main.settings.garage.WriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One field's raw prior state. The restore closes over only that field, never a profile. */
class LastSaveUndo private constructor(
    val key: String,
    @param:StringRes val labelRes: Int,
    val priorState: Any?,
    internal val restore: suspend () -> Boolean,
) {
    companion object {
        fun <T> captured(key: String, @StringRes labelRes: Int, previous: T,
            restore: suspend (T) -> Boolean) = LastSaveUndo(key, labelRes, previous) { restore(previous) }
    }
}

/** Uses the host's writer for both saves and restores; screen lifetime invalidates queued offers. */
class LastSaveUndoState(private val writer: VehicleCostWriter, private val edits: EditSession) {
    private val _lastSave = MutableStateFlow<LastSaveUndo?>(null)
    val lastSave = _lastSave.asStateFlow()
    private var epoch = 0
    private var offering = true

    fun write(action: suspend () -> LastSaveUndo?): Deferred<WriteResult> {
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
        edits.discard(expected.key)
        return writer.write { expected.restore() }
    }

    fun dismiss(expected: LastSaveUndo) { if (_lastSave.value === expected) _lastSave.value = null }
    fun clear() { epoch++; _lastSave.value = null; offering = false }
    fun resume() { offering = true }
}

@Composable
fun LastSaveUndoSnackbar(save: LastSaveUndo?, host: SnackbarHostState,
    onUndo: (LastSaveUndo) -> Unit, onDismiss: (LastSaveUndo) -> Unit) {
    val message = save?.let { stringResource(R.string.economy_field_saved, stringResource(it.labelRes)) }
    val action = stringResource(R.string.economy_field_undo)
    LaunchedEffect(save, host) {
        if (save != null && message != null) {
            when (host.showSnackbar(message, actionLabel = action, duration = SnackbarDuration.Short)) {
                SnackbarResult.ActionPerformed -> onUndo(save)
                SnackbarResult.Dismissed -> onDismiss(save)
            }
        }
    }
}
