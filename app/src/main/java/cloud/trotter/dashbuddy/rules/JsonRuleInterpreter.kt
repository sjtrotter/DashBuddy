package cloud.trotter.dashbuddy.rules

import android.content.Context
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

    var screenRuleset: ScreenRuleset? = null
        private set
    var clickRuleset: ClickRuleset? = null
        private set
    var notificationRuleset: NotificationRuleset? = null
        private set
    var loadedFormatVersion: Int? = null
        private set

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

            for (fileName in files) {
                val path = "$RULES_DIR/$fileName"
                val json = context.assets.open(path).bufferedReader().readText()
                val result = loadSingle(json, source = path) ?: continue
                allScreens += result.screens
                allClicks += result.clicks
                allNotifications += result.notifications
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

            Timber.i(
                "JsonRuleInterpreter: compiled '$source' " +
                    "(screens=${screens.size}, clicks=${clicks.size}, notifications=${notifications.size})"
            )

            CompiledRuleBundle(screens, clicks, notifications)
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
    )

    companion object {
        private const val RULES_DIR = "rules"
        private const val MAX_FILE_BYTES = 1_000_000  // 1 MB
    }
}
