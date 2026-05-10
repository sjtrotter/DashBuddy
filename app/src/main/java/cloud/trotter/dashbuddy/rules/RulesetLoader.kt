package cloud.trotter.dashbuddy.rules

import cloud.trotter.dashbuddy.domain.pipeline.RuleEngineConstants
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Compatibility check per ADR-0003.
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

        // Steps 3+: flow/mode vocabulary validation is deferred to
        // RuleCompiler.parseStateBlock which rejects unknown values at
        // compile time per-rule.

        return null
    }
}
