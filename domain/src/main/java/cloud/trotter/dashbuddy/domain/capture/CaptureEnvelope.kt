package cloud.trotter.dashbuddy.domain.capture

/**
 * A self-describing capture envelope written to disk. Contains the raw
 * payload from a pipeline along with all metadata needed for replay and
 * corpus contribution.
 *
 * Filenames: `{timestamp_ms}__{platform}__{classification_name}__{content_hash_6}.json`
 */
data class CaptureEnvelope<T>(
    val captureId: String,
    val pipelineId: String,
    val schemaId: String,
    val timestamp: Long,
    val platform: String,
    val ruleId: String?,
    val classificationName: String?,
    val metadata: ReplayMetadata,
    val payload: T,
)
