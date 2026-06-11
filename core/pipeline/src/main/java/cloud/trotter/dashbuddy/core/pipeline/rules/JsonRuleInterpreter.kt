package cloud.trotter.dashbuddy.core.pipeline.rules

import android.content.Context
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
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

    /**
     * The loaded rulesets as ONE immutable bundle behind a single volatile
     * reference (#361): readers on flow threads see either the previous
     * complete set or the next complete set, never a half-swapped mix. This is
     * the thread-safety prerequisite for the #192 OTA hot-reload path.
     */
    @Volatile
    private var current: LoadedRulesets = LoadedRulesets()

    val screenRuleset: Ruleset<UiNode>? get() = current.screens
    val clickRuleset: Ruleset<UiNode>? get() = current.clicks
    val notificationRuleset: Ruleset<RawNotificationData>? get() = current.notifications
    val loadedFormatVersion: Int? get() = current.formatVersion

    private data class LoadedRulesets(
        val screens: Ruleset<UiNode>? = null,
        val clicks: Ruleset<UiNode>? = null,
        val notifications: Ruleset<RawNotificationData>? = null,
        val formatVersion: Int? = null,
    )

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

            val allScreens = mutableListOf<CompiledRule<UiNode>>()
            val allClicks = mutableListOf<CompiledRule<UiNode>>()
            val allNotifications = mutableListOf<CompiledRule<RawNotificationData>>()
            val seenRuleIds = mutableMapOf<String, String>() // ruleId → first source path
            var lastFormatVersion: Int? = null

            for (fileName in files) {
                val path = "$RULES_DIR/$fileName"
                val json = context.assets.open(path).bufferedReader().readText()
                val result = loadSingle(json, source = path) ?: continue

                // Collision detection (C3): warn if any rule ID appears in multiple files
                val newIds = result.screens.map { it.id } +
                    result.clicks.map { it.id } +
                    result.notifications.map { it.id }
                for (id in newIds) {
                    val existingSource = seenRuleIds.put(id, path)
                    if (existingSource != null) {
                        Timber.w(
                            "JsonRuleInterpreter: rule ID '%s' declared in both '%s' and '%s'",
                            id, existingSource, path,
                        )
                    }
                }

                allScreens += result.screens
                allClicks += result.clicks
                allNotifications += result.notifications
                result.formatVersion?.let { lastFormatVersion = it }
            }

            current = LoadedRulesets(
                screens = Ruleset(allScreens),
                clicks = Ruleset(allClicks),
                notifications = Ruleset(allNotifications),
                formatVersion = lastFormatVersion,
            )

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
                ?.let { RuleCompiler.compileRules<UiNode>(it, RuleContext.SCREEN) }
                ?: emptyList()
            val clicks = root["clicks"]?.jsonArray
                ?.let { RuleCompiler.compileRules<UiNode>(it, RuleContext.CLICK) }
                ?: emptyList()
            val notifications = root["notifications"]?.jsonArray
                ?.let { RuleCompiler.compileRules<RawNotificationData>(it, RuleContext.NOTIFICATION) }
                ?: emptyList()

            val formatVersion = root["format_version"]?.jsonPrimitive?.int

            Timber.i(
                "JsonRuleInterpreter: compiled '$source' " +
                    "(screens=${screens.size}, clicks=${clicks.size}, notifications=${notifications.size})"
            )

            CompiledRuleBundle(screens, clicks, notifications, formatVersion)
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
        current = LoadedRulesets(
            screens = Ruleset(result.screens),
            clicks = Ruleset(result.clicks),
            notifications = Ruleset(result.notifications),
            formatVersion = result.formatVersion,
        )
    }

    /** Result of compiling a single rule file. */
    data class CompiledRuleBundle(
        val screens: List<CompiledRule<UiNode>>,
        val clicks: List<CompiledRule<UiNode>>,
        val notifications: List<CompiledRule<RawNotificationData>>,
        val formatVersion: Int? = null,
    )

    companion object {
        private const val RULES_DIR = "rules"
        private const val MAX_FILE_BYTES = 1_000_000  // 1 MB
    }
}
