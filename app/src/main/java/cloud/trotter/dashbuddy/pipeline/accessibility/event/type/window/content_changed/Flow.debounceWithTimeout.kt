package cloud.trotter.dashbuddy.pipeline.accessibility.event.type.window.content_changed

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import java.util.concurrent.atomic.AtomicLong

/**
 * Debounce the flow, but GUARANTEES an emission if [maxWaitMs] passes
 * without one.
 *
 * @param debounceMs The standard wait time for silence (e.g. 150ms)
 * @param maxWaitMs The maximum time to wait before forcing an update (e.g. 500ms),
 * preventing "Starvation" if the stream never stops.
 */
fun <T> Flow<T>.debounceWithTimeout(
    debounceMs: Long,
    maxWaitMs: Long
): Flow<T> = channelFlow {
    val lastEmissionTime = AtomicLong(0L)

    collectLatest { value ->
        val now = System.currentTimeMillis()
        val timeSinceLastEmission = now - lastEmissionTime.get()

        if (timeSinceLastEmission >= maxWaitMs) {
            send(value)
            lastEmissionTime.set(now)
        } else {
            delay(debounceMs)
            send(value)
            lastEmissionTime.set(System.currentTimeMillis())
        }
    }
}