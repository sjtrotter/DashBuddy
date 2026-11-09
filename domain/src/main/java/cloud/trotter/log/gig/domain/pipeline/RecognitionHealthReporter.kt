package cloud.trotter.log.gig.domain.pipeline

/**
 * Surfaces a sustained recognition failure to the dasher (#937, one incident per platform since #1560).
 *
 * The contract lives in `:domain` so `:core:pipeline` can call it while keeping its
 * `:domain`-only dependency (the [LocaleBoundaryReporter] / `PlatformPreferences` inversion,
 * #938/#355); the implementation lives in `:app`, which owns the notification surface.
 *
 * Recognition dying mid-dash is the unfilled quadrant of the #909 silent-death lesson: the
 * pipeline keeps running, every frame classifies UNKNOWN (or a matched rule reads nothing), and the
 * dasher sees an app that simply stops narrating. Without this the only detection is a desk pull
 * days later.
 *
 * The contract is data only: the implementation decides how the notice looks, and #640 adds the
 * notice's actions there, not here.
 *
 * **Fail-open:** the implementation must neither throw nor block. Both calls come from the
 * monitor's IO coroutine, in order; a diagnostic must never be able to disturb frame processing.
 */
interface RecognitionHealthReporter {

    /**
     * The [incident]'s one notice is due. Called at most **once per incident and once per 24 h per
     * platform** — the incident owner (`RecognitionHealthMonitor`) holds both gates, so the
     * implementation posts unconditionally and needs no state of its own.
     *
     * The incident is keyed by `Platform.wire`, never a package or a display string; the
     * implementation resolves display copy from it.
     */
    fun onIncidentNotice(incident: RecognitionIncident)

    /** The platform has no open incident; withdraw its notice if one is showing. */
    fun onIncidentClosed(platformWire: String)

    /** Reports nothing. */
    object NoOp : RecognitionHealthReporter {
        override fun onIncidentNotice(incident: RecognitionIncident) = Unit
        override fun onIncidentClosed(platformWire: String) = Unit
    }
}
