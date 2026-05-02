package cloud.trotter.dashbuddy.core.data.capture

/**
 * Pipeline-side capture interface. Pipelines offer raw payloads after
 * classification; the bus decides whether/how to persist them.
 *
 * Release builds bind [NoOpCaptureBus]. Debug builds bind [CaptureService]
 * (once implemented in Phase 7).
 */
interface CaptureBus {
    fun <T> offer(
        pipelineId: String,
        payload: T,
        ruleId: String?,
    )
}
