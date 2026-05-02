package cloud.trotter.dashbuddy.core.data.capture

import javax.inject.Inject

/**
 * No-op capture bus for release builds (and until CaptureService is implemented).
 */
class NoOpCaptureBus @Inject constructor() : CaptureBus {
    override fun <T> offer(pipelineId: String, payload: T, ruleId: String?) {
        // Intentionally empty — release builds don't capture.
    }
}
