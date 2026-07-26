package cloud.trotter.dashbuddy.core.pipeline

/**
 * Log-safe short identifier for a marker constant (#862).
 *
 * The scrub/backstop sites in [CaptureWriter] WARN that the privacy layer fired, and they name
 * WHICH marker tripped. Naming it **verbatim** made the WARN self-defeating: the shareable-log
 * sink ([cloud.trotter.dashbuddy.core.data.log.LogScrubber], bound to
 * [SensitiveTextMarkers.findMarker]) scans every INFO+ line and redacts any line carrying a
 * marker — so `Capture scrubbed: UNKNOWN click hit sensitive marker 'Transfer out'` was itself
 * redacted to `[scrubbed:Transfer out]` in the exported bug report, losing exactly the event the
 * export most wants to record ("the privacy layer fired, here's where").
 *
 * The fix keeps the sink **zero-trust** — no allowlist to rot, the scan still reads every byte of
 * every line — by making OUR OWN diagnostic reference the marker non-verbatim: the id is the
 * marker's first two alphanumeric characters plus its length (`"Transfer out"` → `Tr12`), which
 * cannot contain a marker (every marker is longer than its own id's alphabetic head) and so
 * survives the scan untouched. A genuine third-party string that happens to carry a marker is
 * unaffected and still scrubbed.
 *
 * **Decoding at the desk:** an id maps back against the marker SSOTs it came from —
 * [SensitiveTextMarkers.KEYWORDS] / its shape patterns / its fail-closed sentinel, and
 * [CustomerTextMarkers.MARKERS]. Ids are pinned collision-free per SSOT by `MarkerLogIdTest`, so
 * the map is unambiguous.
 */
object MarkerLogId {

    /**
     * The log-safe id for [marker] — first two alphanumeric characters + total length.
     *
     * [marker] is always one of OUR constants (a marker from a SSOT list, a `shape:<pattern>`
     * name, or a fail-closed sentinel), never scanned third-party text; the transform exists to
     * stop the constant from tripping the sink's own scan, not to hide untrusted content.
     */
    fun of(marker: String): String {
        val head = marker.asSequence().filter { it.isLetterOrDigit() }.take(HEAD_CHARS)
            .joinToString(separator = "")
        return if (head.isEmpty()) "?${marker.length}" else "$head${marker.length}"
    }

    private const val HEAD_CHARS = 2
}
