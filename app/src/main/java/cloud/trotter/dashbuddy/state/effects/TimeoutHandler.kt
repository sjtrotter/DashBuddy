package cloud.trotter.dashbuddy.state.effects

import cloud.trotter.dashbuddy.state.StateManagerV2
import cloud.trotter.dashbuddy.state.event.TimeoutEvent
import cloud.trotter.dashbuddy.state.model.TimeoutType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log

object TimeoutHandler {
    private val jobs = mutableMapOf<TimeoutType, Job>()

    fun schedule(scope: CoroutineScope, durationMs: Long, type: TimeoutType) {
        jobs[type]?.cancel() // Cancel old
        jobs[type] = scope.launch(Dispatchers.Default) {
            delay(durationMs)

            // TIME IS UP!
            Log.w("TimeoutHandler", "Timer Expired! : $type")

            // Inject a special context into the machine
            StateManagerV2.dispatch(
                TimeoutEvent(
                    timestamp = System.currentTimeMillis(),
                    type = type
                )
            )

            // Clean up from the map
            jobs.remove(type)
        }
    }

    fun cancel(type: TimeoutType) {
        jobs[type]?.cancel()
    }
}