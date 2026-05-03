package cloud.trotter.dashbuddy.core.data.capture

/**
 * Pipeline-side capture interface. Pipelines build a [CaptureEnvelope]
 * via [EnvelopeBuilder], pre-serialize it to JSON, and pass the result
 * here. The bus decides whether/how to persist and returns the captureId
 * if written (null if deduped or skipped).
 *
 * Release builds bind [NoOpCaptureBus]. Debug builds bind [DiskCaptureBus].
 */
interface CaptureBus {

    /**
     * Write a capture envelope to disk.
     *
     * @param captureId  UUID assigned to this capture by the pipeline.
     * @param source     Pipeline identifier (e.g., "accessibility.window").
     * @param classification Matched screen/rule name (e.g., "MAIN_MAP_IDLE"), null for unknown.
     * @param platform   Platform identifier (e.g., "doordash").
     * @param envelopeJson Pre-serialized CaptureEnvelope JSON.
     * @param contentHash Optional structural hash for per-bucket deduplication.
     * @return The [captureId] if the capture was written, null if deduped/skipped.
     */
    fun offer(
        captureId: String,
        source: String,
        classification: String?,
        platform: String,
        envelopeJson: String,
        contentHash: Int? = null,
    ): String?
}
