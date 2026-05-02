package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.pipeline.RuleEngineConstants
import cloud.trotter.dashbuddy.domain.pipeline.StateMachineContract
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Seven-step compatibility check per ADR-0003.
 *
 * Validates a parsed ruleset JSON root before it reaches [RuleCompiler].
 * If any check fails, the entire bundle is rejected and the caller falls
 * back to the previous good version.
 */
object RulesetLoader {

    /**
     * Validate a ruleset root object. Returns null if validation passes,
     * or a rejection reason string if it fails.
     */
    fun validate(root: JsonObject, source: String): String? {
        // Step 1: format_version must be present and within engine support range
        val formatVersion = root["format_version"]?.jsonPrimitive?.int
            ?: return "missing format_version"
        if (formatVersion > RuleEngineConstants.MAX_SUPPORTED_FORMAT_VERSION) {
            return "format_version $formatVersion exceeds max supported ${RuleEngineConstants.MAX_SUPPORTED_FORMAT_VERSION}"
        }

        // Step 2: platform_id must be present
        val platformId = root["platform_id"]?.jsonPrimitive?.content
        if (platformId.isNullOrBlank()) {
            return "missing or blank platform_id"
        }

        // Step 3: pipeline version compatibility (if declared)
        val pipelinesObj = root["pipelines"]?.jsonObject
        if (pipelinesObj != null) {
            for ((pipelineId, versionElem) in pipelinesObj) {
                val declared = versionElem.jsonPrimitive.int
                val supported = cloud.trotter.dashbuddy.domain.pipeline.PipelineRegistry
                    .pipelines[pipelineId]
                if (supported == null) {
                    Timber.w("RulesetLoader: unknown pipeline '$pipelineId' in $source (ignored)")
                    continue
                }
                if (declared > supported) {
                    return "pipeline '$pipelineId' version $declared exceeds supported $supported"
                }
            }
        }

        // Step 4: state_machine version compatibility (if declared)
        val smObj = root["state_machine"]?.jsonObject
        if (smObj != null) {
            val majorDeclared = smObj["api_version_major"]?.jsonPrimitive?.int ?: 0
            if (majorDeclared > StateMachineContract.API_VERSION_MAJOR) {
                return "state_machine major version $majorDeclared exceeds supported ${StateMachineContract.API_VERSION_MAJOR}"
            }
        }

        // Steps 5-7: flow/mode vocabulary validation is deferred to
        // RuleCompiler.parseStateBlock which rejects unknown values at
        // compile time per-rule.

        return null
    }
}
