package cloud.trotter.dashbuddy.core.data.capture

import cloud.trotter.dashbuddy.domain.capture.CaptureSchema
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.pipeline.PipelineRegistry
import cloud.trotter.dashbuddy.domain.pipeline.RuleEngineConstants
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.encodeToString
import java.util.UUID

/**
 * Builds a capture envelope from pipeline context and a raw payload,
 * serializes it to JSON. Stateless utility — all context is passed per call.
 *
 * Lives in `:core:data` because it depends on kotlinx.serialization for
 * JSON encoding — `:domain` is pure Kotlin.
 */
object EnvelopeBuilder {

    private val json = Json { prettyPrint = true }

    /**
     * Build a capture envelope and serialize it to JSON.
     *
     * @return [CaptureResult] containing captureId, envelope JSON, and content hash.
     */
    fun <T> build(
        pipelineId: String,
        schema: CaptureSchema<T>,
        platform: String,
        ruleId: String?,
        classificationName: String?,
        payload: T,
        contentHash: Int? = null,
        metadata: ReplayMetadata? = null,
        windowContext: WindowContextDto? = null,
    ): CaptureResult {
        val captureId = UUID.randomUUID().toString()
        val payloadJsonStr = schema.serialize(payload)
        val payloadElement = json.parseToJsonElement(payloadJsonStr)

        val meta = metadata
        val metadataDto = ReplayMetadataDto(
            engineVersion = meta?.engineVersion ?: RuleEngineConstants.VERSION,
            rulesetFormatVersion = meta?.rulesetFormatVersion,
            rulesetReleaseTag = meta?.rulesetReleaseTag,
            rulesetSignature = meta?.rulesetSignature,
            pipelineVersions = meta?.pipelineVersions ?: PipelineRegistry.pipelines,
            stateMachineApiVersion = meta?.stateMachineApiVersion,
            appVersion = meta?.appVersion,
            deviceFingerprint = meta?.deviceFingerprint,
        )

        val envelope = CaptureEnvelopeDto(
            captureId = captureId,
            pipelineId = pipelineId,
            schemaId = schema.schemaId,
            timestamp = System.currentTimeMillis(),
            platform = platform,
            ruleId = ruleId,
            classificationName = classificationName,
            metadata = metadataDto,
            payload = payloadElement,
            windowContext = windowContext,
        )

        return CaptureResult(
            captureId = captureId,
            envelopeJson = json.encodeToString(envelope),
            contentHash = contentHash,
        )
    }
}

data class CaptureResult(
    val captureId: String,
    val envelopeJson: String,
    val contentHash: Int?,
)

@Serializable
internal data class CaptureEnvelopeDto(
    val captureId: String,
    val pipelineId: String,
    val schemaId: String,
    val timestamp: Long,
    val platform: String,
    val ruleId: String?,
    val classificationName: String?,
    val metadata: ReplayMetadataDto,
    val payload: JsonElement,
    val windowContext: WindowContextDto? = null,
)

@Serializable
internal data class ReplayMetadataDto(
    val engineVersion: Int,
    val rulesetFormatVersion: Int? = null,
    val rulesetReleaseTag: String? = null,
    val rulesetSignature: String? = null,
    val pipelineVersions: Map<String, Int> = emptyMap(),
    val stateMachineApiVersion: String? = null,
    val appVersion: String? = null,
    val deviceFingerprint: String? = null,
)

@Serializable
data class WindowContextDto(
    val windowId: Int,
    val windowType: Int,
    val windowTitle: String? = null,
    val windowLayer: Int,
    val isActive: Boolean,
    val isFocused: Boolean,
    val totalWindowCount: Int,
)
