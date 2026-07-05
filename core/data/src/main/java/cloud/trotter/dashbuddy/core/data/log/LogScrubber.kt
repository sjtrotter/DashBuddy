package cloud.trotter.dashbuddy.core.data.log

/**
 * The fail-closed PII scrub applied at the shareable-log sink (#551, CLAUDE.md Principle 7 —
 * "the log is two products": an on-device DEBUG firehose and a PII-safe INFO+ slice a user can
 * export as a bug report).
 *
 * Placement rationale: the marker SSOT (`SensitiveTextMarkers`) lives in `:core:pipeline`, which
 * `:core:data` does NOT depend on (see CLAUDE.md § Module Structure). Rather than add a module edge
 * or mirror the marker list, [LogRepository] depends on this thin function type; `:app` (which sees
 * both modules) binds it to `SensitiveTextMarkers::findMarker`, keeping ONE marker definition.
 *
 * Contract: return a non-null **marker name** (a safe constant — a keyword name, a shape id, or the
 * `normalize-error` sentinel; never the scanned text) when [line] contains a sensitive/PII token,
 * else `null` when the line is clean. Implementations are expected to fail closed internally (a
 * normalization throw returns a sentinel, not `null`), and [LogRepository] additionally treats any
 * throw out of this call — and an entirely unbound scrubber — as a hit, so the shareable sink can
 * never emit un-scrubbed text.
 */
fun interface LogScrubber {
    fun findMarker(line: String): String?
}
