package cloud.trotter.dashbuddy.ui.main.settings.garage

import cloud.trotter.dashbuddy.core.data.vehicle.VehiclesUnreadableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * NONE/RETRY/UNREADABLE mirror :feature:bubble's OdometerWriteFailure, which cannot depend on :app.
 * GONE is the editor's stale-vehicle outcome.
 */
enum class GarageWriteFailure { NONE, RETRY, UNREADABLE, GONE }

internal class GarageWriter(private val scope: CoroutineScope, private val tag: String) {
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _failure = MutableStateFlow(GarageWriteFailure.NONE)
    val failure = _failure.asStateFlow()

    /** List rows keep their previous outcome on refusal; editors supply GONE. */
    fun write(
        refused: GarageWriteFailure? = null,
        onDone: () -> Unit = {},
        action: suspend () -> Boolean,
    ) {
        if (_busy.value) return
        _busy.value = true
        scope.launch {
            try {
                val wrote = withContext(NonCancellable) { action() }
                if (wrote) {
                    _failure.value = GarageWriteFailure.NONE
                    onDone()
                } else if (refused != null) {
                    _failure.value = refused
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _failure.value = if (error is VehiclesUnreadableException) GarageWriteFailure.UNREADABLE else GarageWriteFailure.RETRY
                Timber.tag(tag).w("%s", error.javaClass.simpleName)
            } finally {
                _busy.value = false
            }
        }
    }
}
