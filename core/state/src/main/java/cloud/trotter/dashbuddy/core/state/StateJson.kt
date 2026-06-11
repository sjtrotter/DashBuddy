package cloud.trotter.dashbuddy.core.state

import kotlinx.serialization.json.Json

/**
 * The one Json configuration for state persistence — snapshots, the observation
 * journal, and event payloads (#353). `ignoreUnknownKeys` + `encodeDefaults` give
 * schema-drift tolerance: a field added later decodes as its default from old
 * rows, and an unknown old field is skipped — instead of Gson's reflective
 * instantiation silently nulling non-null Kotlin fields. Decode failures must be
 * handled LOUDLY by callers; silent degradation hid replay corruption before.
 */
internal val StateJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
