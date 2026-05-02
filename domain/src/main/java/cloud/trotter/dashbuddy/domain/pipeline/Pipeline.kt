package cloud.trotter.dashbuddy.domain.pipeline

import cloud.trotter.dashbuddy.domain.capture.CaptureSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.merge

/**
 * A pipeline transforms raw events from a source into typed [Observation]s.
 *
 * Each pipeline is identified by [pipelineId] and stamps its outputs with
 * [apiVersion] per ADR-0003 Layer 1.
 *
 * @param RAW The raw payload type from the source (e.g., UiNode, RawNotificationData).
 * @param OUT The observation subtype this pipeline produces.
 */
interface Pipeline<RAW, OUT : Observation> {
    val pipelineId: String
    val apiVersion: Int
    fun output(): Flow<OUT>
}

/**
 * A pipeline that supports capture of its raw payloads for regression testing
 * and corpus contribution.
 */
interface CapturablePipeline<RAW, OUT : Observation> : Pipeline<RAW, OUT> {
    val captureSchema: CaptureSchema<RAW>
}

/**
 * Merges the output of multiple child pipelines into a single flow.
 * Adding a new sub-pipeline is a Hilt bind — no changes to existing code.
 */
abstract class CompositePipeline<OUT : Observation>(
    private val children: List<Pipeline<*, OUT>>,
) : Pipeline<Nothing, OUT> {
    final override fun output(): Flow<OUT> =
        merge(*children.map { it.output() }.toTypedArray())
}
