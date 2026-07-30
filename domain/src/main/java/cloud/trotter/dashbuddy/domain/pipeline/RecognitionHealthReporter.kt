package cloud.trotter.dashbuddy.domain.pipeline

/**
 * Surfaces a sustained recognition failure to the dasher (#937).
 *
 * The contract lives in `:domain` so `:core:pipeline` can call it while keeping its
 * `:domain`-only dependency (the [LocaleBoundaryReporter] / `PlatformPreferences` inversion,
 * #938/#355); the implementation lives in `:app`, which owns the notification surface.
 *
 * Recognition dying mid-dash is the unfilled quadrant of the #909 silent-death lesson: the
 * pipeline keeps running, every frame classifies UNKNOWN, and the dasher sees an app that
 * simply stops narrating. Without this the only detection is a desk pull days later.
 */
fun interface RecognitionHealthReporter {

    /**
     * A platform's recognition has degraded past the alarm threshold.
     *
     * Called at most **once per platform per process** — the sensing layer owns that edge gate,
     * so the implementation is free to post unconditionally and needs no state of its own.
     *
     * **Fail-open:** the implementation must neither throw nor block. This is called from the
     * sensing hot path; a diagnostic must never be able to disturb frame processing.
     *
     * @param platformWire the `Platform.wire` of the degraded platform (`"doordash"`, `"uber"`).
     *   A wire, never a package or a display string — the caller keys by the registry, and the
     *   implementation resolves display copy from it.
     * @param unknownPercent the share of the sampled window that classified UNKNOWN, 0..100.
     */
    fun onRecognitionDegraded(platformWire: String, unknownPercent: Int)
}
