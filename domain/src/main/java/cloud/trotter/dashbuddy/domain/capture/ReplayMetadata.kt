package cloud.trotter.dashbuddy.domain.capture

/**
 * Version stamps and provenance information attached to every observation
 * and capture envelope. Enables deterministic replay of event streams
 * against known engine/ruleset/pipeline versions.
 *
 * See ADR-0003 for the four-layer versioning model.
 */
data class ReplayMetadata(
    val engineVersion: Int,
    val rulesetFormatVersion: Int? = null,
    val rulesetReleaseTag: String? = null,
    val rulesetSignature: String? = null,
    val pipelineVersions: Map<String, Int> = emptyMap(),
    val stateMachineApiVersion: String? = null,
    val appVersion: String? = null,
    val deviceFingerprint: String? = null,
) {
    companion object {
        val EMPTY = ReplayMetadata(engineVersion = 0)
    }
}
