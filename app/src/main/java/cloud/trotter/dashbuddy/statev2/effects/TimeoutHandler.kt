package cloud.trotter.dashbuddy.statev2.effects

import cloud.trotter.dashbuddy.state.StateContext
import cloud.trotter.dashbuddy.statev2.StateManagerV2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import cloud.trotter.dashbuddy.log.Logger as Log

object TimeoutHandler {
    private var job: Job? = null

    fun schedule(scope: CoroutineScope, timestamp: Long) {
        job?.cancel() // Cancel old
        job = scope.launch(Dispatchers.Default) {
            val waitMs = timestamp - System.currentTimeMillis()
            if (waitMs > 0) delay(waitMs)

            // TIME IS UP!
            Log.w("TimeoutHandler", "Dash Pause Timer Expired!")

            // Inject a special context into the machine
            StateManagerV2.dispatch(
                StateContext(
                    timestamp = System.currentTimeMillis(),
                    eventType = 0,
                    eventTypeString = "TIMEOUT",
                    isTimeout = true // <--- The signal
                )
            )
        }
    }

    fun cancel() {
        job?.cancel()
    }
}