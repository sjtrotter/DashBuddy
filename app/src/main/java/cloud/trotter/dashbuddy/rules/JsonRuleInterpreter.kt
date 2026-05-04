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
 * On [loadDefaults], reads `rules.default.json` from app assets, parses it, and compiles
 * each rule's predicate tree into a JVM lambda via [RuleCompiler]. After this call the
 * three rulesets are available for use in the pipeline classifiers.
 *
 * Security checks applied before parsing:
 * - File size ≤ [MAX_FILE_BYTES] (1 MB)
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

    /** Load the bundled default rules from `assets/rules.default.json`. */
    fun loadDefaults() {
        try {
            val json = context.assets.open(ASSET_NAME).bufferedReader().readText()
            load(json, source = ASSET_NAME)
        } catch (e: Exception) {
            Timber.e(e, "JsonRuleInterpreter: failed to open $ASSET_NAME")
        }
    }

    /**
     * Parse and compile a rules JSON string.
     * Called by [loadDefaults] and will be called by the CDN fetch path in Phase A3+.
     */
    fun load(jsonString: String, source: String = "unknown") {
        if (jsonString.length > MAX_FILE_BYTES) {
            Timber.e("JsonRuleInterpreter: $source exceeds size limit (${jsonString.length} bytes)")
            return
        }

        try {
            val root = Json.parseToJsonElement(jsonString).jsonObject

            // ADR-0003 seven-step compatibility check
            val rejection = RulesetLoader.validate(root, source)
            if (rejection != null) {
                Timber.e("JsonRuleInterpreter: rejected '$source': $rejection")
                return
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

            screenRuleset = ScreenRuleset(screens)
            clickRuleset = ClickRuleset(clicks)
            notificationRuleset = NotificationRuleset(notifications)
            loadedFormatVersion = root["format_version"]?.jsonPrimitive?.int

            Timber.i(
                "JsonRuleInterpreter: loaded from '$source' " +
                    "(screens=${screens.size}, clicks=${clicks.size}, notifications=${notifications.size})"
            )
        } catch (e: RuleCompileException) {
            Timber.e(e, "JsonRuleInterpreter: compile error in '$source'")
        } catch (e: Exception) {
            Timber.e(e, "JsonRuleInterpreter: parse error in '$source'")
        }
    }

    companion object {
        private const val ASSET_NAME = "rules.default.json"
        private const val MAX_FILE_BYTES = 1_000_000  // 1 MB
    }
}
