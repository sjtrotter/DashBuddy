package cloud.trotter.dashbuddy.core.data.capture

import cloud.trotter.dashbuddy.domain.capture.CaptureBus
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * No-op capture bus, bound in RELEASE builds (#346): third-party screen
 * content is never persisted. Logs once at startup so an accidentally-dashed
 * release build is diagnosable when a session's captures folder is empty.
 */
@Singleton
class NoOpCaptureBus @Inject constructor() : CaptureBus {

    init {
        Timber.i("Capture persistence disabled (release build) — no envelopes will be written")
    }

    /**
     * Release builds never persist (#435 item 5): reporting `false` lets [CaptureWriter]
     * skip the full envelope-build pipeline whose only output would be discarded by
     * [offer]. Structural, not a call-site guard — the sink itself declares it is off.
     */
    override val isEnabled: Boolean = false

    override fun offer(
        captureId: String,
        source: String,
        classification: String?,
        platform: String,
        envelopeJson: String,
        contentHash: Int?,
    ): String? = null
}
