package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.content_changed

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Debounce the flow, but GUARANTEES an emission if [maxWaitMs] passes
 * without one.
 * * @param debounceMs The standard wait time for silence (e.g. 150ms)
 * @param maxWaitMs The maximum time to wait before forcing an update (e.g. 500ms),
 * preventing "Starvation" if the stream never stops.
 */
fun <T> Flow<T>.debounceWithTimeout(
    debounceMs: Long,
    maxWaitMs: Long
): Flow<T> = channelFlow {
    // Track when we last successfully sent data downstream
    var lastEmissionTime = 0L

    collectLatest { value ->
        val now = System.currentTimeMillis()

        // Check how long we've been holding our breath
        val timeSinceLastEmission = now - lastEmissionTime

        if (timeSinceLastEmission >= maxWaitMs) {
            // STARVATION PROTECTION:
            // The stream is too noisy (infinite spinner/map updates).
            // Stop waiting for silence and just send what we have NOW.
            send(value)
            lastEmissionTime = now
        } else {
            // STANDARD BEHAVIOR:
            // Wait for silence. If a new value comes in before
            // 'debounceMs' finishes, this block is cancelled and restarts.
            delay(debounceMs)
            send(value)
            lastEmissionTime = System.currentTimeMillis()
        }
    }
}