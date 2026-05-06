package cloud.trotter.dashbuddy.rules

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that owns the compiled rule rulesets.
 *
 * On [loadDefaults], scans the `assets/rules/` directory for per-platform JSON files,
 * validates and compiles each independently, then merges them into the combined rulesets.
 * A malformed file is logged and skipped — it does not prevent other platforms from loading.
 *
 * Security checks applied before parsing:
 * - File size ≤ [MAX_FILE_BYTES] (1 MB) per file
 * - [RuleCompiler.MAX_DEPTH] caps nesting depth during compilation
 * - [RuleCompiler.MAX_REGEX_LENGTH] caps regex patterns during compilation
 */
@Singleton
class JsonRuleInterpreter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    var screenRuleset: ScreenRuleset? = null
        private set
    var clickRuleset: ClickRuleset? = null
        private set
    var notificationRuleset: NotificationRuleset? = null
        private set
    var loadedFormatVersion: Int? = null
        private set

    /** Per-platform pipeline configs parsed from ruleset headers. Key = platform wire name. */
    private val _pipelineConfigs = mutableMapOf<String, Map<String, PipelineConfig>>()

    /** Get pipeline config for a specific platform and pipeline. */
    fun getPipelineConfig(platformWire: String, pipelineId: String): PipelineConfig? =
        _pipelineConfigs[platformWire]?.get(pipelineId)

    /** Load all bundled rule files from `assets/rules/`. */
    fun loadDefaults() {
        try {
            val files = context.assets.list(RULES_DIR)
                ?.filter { it.endsWith(".json") }
                ?: emptyList()

            if (files.isEmpty()) {
                Timber.w("JsonRuleInterpreter: no rule files found in $RULES_DIR/")
                return
            }

            val allScreens = mutableListOf<CompiledScreenRule>()
            val allClicks = mutableListOf<CompiledClickRule>()
            val allNotifications = mutableListOf<CompiledNotificationRule>()
            _pipelineConfigs.clear()

            for (fileName in files) {
                val path = "$RULES_DIR/$fileName"
                val json = context.assets.open(path).bufferedReader().readText()
                val result = loadSingle(json, source = path) ?: continue
                allScreens += result.screens
                allClicks += result.clicks
                allNotifications += result.notifications
                if (result.platformWire != null && result.pipelineConfigs.isNotEmpty()) {
                    _pipelineConfigs[result.platformWire] = result.pipelineConfigs
                }
            }

            screenRuleset = ScreenRuleset(allScreens)
            clickRuleset = ClickRuleset(allClicks)
            notificationRuleset = NotificationRuleset(allNotifications)

            Timber.i(
                "JsonRuleInterpreter: loaded %d file(s) from %s/ " +
                    "(screens=%d, clicks=%d, notifications=%d)",
                files.size, RULES_DIR, allScreens.size, allClicks.size, allNotifications.size,
            )
        } catch (e: Exception) {
            Timber.e(e, "JsonRuleInterpreter: failed to load rules directory")
        }
    }

    /**
     * Parse and compile a single rules JSON string.
     * Used by [loadDefaults] and will be used by the CDN fetch path in Phase A3+.
     *
     * @return compiled rule lists, or null if validation/compilation fails.
     */
    fun loadSingle(jsonString: String, source: String = "unknown"): CompiledRuleBundle? {
        if (jsonString.length > MAX_FILE_BYTES) {
            Timber.e("JsonRuleInterpreter: $source exceeds size limit (${jsonString.length} bytes)")
            return null
        }

        return try {
            val root = Json.parseToJsonElement(jsonString).jsonObject

            // ADR-0003 seven-step compatibility check
            val rejection = RulesetLoader.validate(root, source)
            if (rejection != null) {
                Timber.e("JsonRuleInterpreter: rejected '$source': $rejection")
                return null
            }

            val screens = root["screens"]?.jsonArray
                ?.let { RuleCompiler.compileScreenRules(it) }
                ?: emptyList()
            val clicks = root["clicks"]?.jsonArray
                ?.let { RuleCompiler.compileClickRules(it) }
                ?: emptyList()
            val notifications = root["notifications"]?.jsonArray
                ?.let { RuleCompiler.compileNotificationRules(it) }
                ?: emptyList()

            loadedFormatVersion = root["format_version"]?.jsonPrimitive?.int

            // Extract platform wire and pipeline configs
            val platformId = root["platform_id"]?.jsonPrimitive?.content
            val platformWire = platformId?.substringBefore('.')
            val pipelineConfigs = parsePipelineConfigs(root["pipelines"]?.jsonObject)

            Timber.i(
                "JsonRuleInterpreter: compiled '$source' " +
                    "(screens=${screens.size}, clicks=${clicks.size}, notifications=${notifications.size})"
            )

            CompiledRuleBundle(screens, clicks, notifications, platformWire, pipelineConfigs)
        } catch (e: RuleCompileException) {
            Timber.e(e, "JsonRuleInterpreter: compile error in '$source'")
            null
        } catch (e: Exception) {
            Timber.e(e, "JsonRuleInterpreter: parse error in '$source'")
            null
        }
    }

    /**
     * Load a single ruleset and replace all current rules.
     * Kept for backward compatibility with CDN hot-reload path.
     */
    fun load(jsonString: String, source: String = "unknown") {
        val result = loadSingle(jsonString, source) ?: return
        screenRuleset = ScreenRuleset(result.screens)
        clickRuleset = ClickRuleset(result.clicks)
        notificationRuleset = NotificationRuleset(result.notifications)
    }

    /** Result of compiling a single rule file. */
    data class CompiledRuleBundle(
        val screens: List<CompiledScreenRule>,
        val clicks: List<CompiledClickRule>,
        val notifications: List<CompiledNotificationRule>,
        val platformWire: String? = null,
        val pipelineConfigs: Map<String, PipelineConfig> = emptyMap(),
    )

    /** Per-pipeline configuration declared in a ruleset header. */
    data class PipelineConfig(
        val version: Int,
        val allowOngoing: Boolean = false,
    )

    private fun parsePipelineConfigs(pipelinesObj: JsonObject?): Map<String, PipelineConfig> {
        if (pipelinesObj == null) return emptyMap()
        val configs = mutableMapOf<String, PipelineConfig>()
        for ((pipelineId, elem) in pipelinesObj) {
            val config = when (elem) {
                is JsonPrimitive -> PipelineConfig(version = elem.int)
                is JsonObject -> PipelineConfig(
                    version = elem["version"]?.jsonPrimitive?.int ?: 0,
                    allowOngoing = elem["allowOngoing"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
                else -> PipelineConfig(version = 0)
            }
            configs[pipelineId] = config
        }
        return configs
    }

    companion object {
        private const val RULES_DIR = "rules"
        private const val MAX_FILE_BYTES = 1_000_000  // 1 MB
    }
}
