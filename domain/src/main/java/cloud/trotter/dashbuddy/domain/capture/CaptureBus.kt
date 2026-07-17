package cloud.trotter.dashbuddy.domain.capture

/**
 * Pipeline-side capture interface. Pipelines build a [CaptureEnvelope]
 * via [EnvelopeBuilder], pre-serialize it to JSON, and pass the result
 * here. The bus decides whether/how to persist and returns the captureId
 * if written (null if deduped or skipped).
 *
 * Release builds bind [NoOpCaptureBus]; debug builds bind [DiskCaptureBus] —
 * enforced by the per-variant `CaptureBusModule` source sets in `:core:data`
 * (`src/debug` / `src/release`, #346).
 */
interface CaptureBus {

    /**
     * Whether this bus persists envelopes (#435 item 5). When `false` — the release
     * [NoOpCaptureBus], which discards every offer — [CaptureWriter] skips the entire
     * tree→DTO→JSON→reparse→pretty-print envelope build structurally, since there is
     * nothing to build for a sink that throws the result away. Defaults `true`, so the
     * debug [DiskCaptureBus] and every test fake keep exercising the full build path
     * (envelope content / redaction are debug-tested and must stay byte-identical).
     */
    val isEnabled: Boolean get() = true

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
