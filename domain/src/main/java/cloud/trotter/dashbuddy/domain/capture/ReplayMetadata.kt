package cloud.trotter.dashbuddy.domain.capture

import kotlinx.serialization.Serializable

/**
 * Version stamps and provenance information attached to every observation
 * and capture envelope. Enables deterministic replay of event streams
 * against known engine/ruleset/pipeline versions.
 *
 * See ADR-0003 for the four-layer versioning model.
 */
@Serializable
data class ReplayMetadata(
    val engineVersion: Int,
    val rulesetFormatVersion: Int? = null,
    val rulesetReleaseTag: String? = null,
    val rulesetSignature: String? = null,
    val pipelineVersions: Map<String, Int> = emptyMap(),
    val stateMachineApiVersion: String? = null,
    val appVersion: String? = null,
    val deviceFingerprint: String? = null,
    /**
     * The `versionName` of the OBSERVED third-party app — the package this frame came
     * from — at the moment it was classified (#937). Recognition anchors break when the
     * platform ships an update, and without this stamp a desk pull can see the breakage
     * but not correlate it with the release that caused it.
     *
     * Null whenever the package could not be resolved (no package on the frame, an
     * uninstalled/unknown package, a PackageManager failure): the stamp is a diagnostic
     * and fails OPEN. `Json.encodeDefaults` is false, so a null keeps the serialized
     * envelope byte-identical to a pre-#937 one — committed corpus fixtures and the
     * parse goldens carry no such key and must never be required to.
     *
     * PII posture: a package's version string is a platform-app fact, not user data.
     */
    val platformAppVersion: String? = null,
) {
    companion object {
        val EMPTY = ReplayMetadata(engineVersion = 0)
    }
}
