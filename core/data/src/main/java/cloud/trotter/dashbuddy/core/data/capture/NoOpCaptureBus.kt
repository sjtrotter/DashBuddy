package cloud.trotter.dashbuddy.core.data.capture

import javax.inject.Inject

/**
 * No-op capture bus for release builds. Discards all captures.
 */
class NoOpCaptureBus @Inject constructor() : CaptureBus {
    override fun offer(
        captureId: String,
        source: String,
        classification: String?,
        platform: String,
        envelopeJson: String,
        contentHash: Int?,
    ): String? = null
}
