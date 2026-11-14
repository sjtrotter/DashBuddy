package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/** One rules file compiled on its own, before the generation-wide checks (#1568). */
internal class CompiledRuleFile(
    val bundle: JsonRuleInterpreter.CompiledRuleBundle,
    val platformId: String,
    /** The raw rule objects by section and id, read by [RemoteRuleAdmission]. */
    val rawRules: Map<RuleContext, Map<String, JsonObject>>,
    /** Ids of the rules the compiler skipped for a compile error (#293 item 4). */
    val isolatedRuleIds: List<String>,
)

/** The 1 MB per-file limit, for every source. */
internal const val MAX_RULE_FILE_BYTES = 1_000_000

/**
 * Parse and compile one rules JSON string. Returns null when the file fails validation or
 * compilation as a whole. Rules the compiler isolated are listed in
 * [CompiledRuleFile.isolatedRuleIds]; whether that rejects the generation is the caller's call.
 */
internal fun compileRuleFile(jsonString: String, source: String): CompiledRuleFile? {
    if (jsonString.length > MAX_RULE_FILE_BYTES) {
        Timber.tag("Rules").e("JsonRuleInterpreter: $source exceeds size limit (${jsonString.length} bytes)")
        return null
    }

    return try {
        // #590: bounded parse — a depth pre-scan rejects pathologically
        // nested JSON as a typed RuleCompileException BEFORE the recursive
        // parser can StackOverflowError (an Error escaping this catch = fail
        // open). The RuleCompileException lands in the catch below and skips
        // the file per the malformed-file policy.
        val root = RuleCompiler.parseBoundedJson(jsonString).jsonObject

        // ADR-0003 seven-step compatibility check
        val rejection = RulesetLoader.validate(root, source)
        if (rejection != null) {
            Timber.tag("Rules").e("JsonRuleInterpreter: rejected '$source': $rejection")
            return null
        }

        // #419: cap the total rule count from the RAW arrays, BEFORE compiling
        // — a 1 MB bundle passes MAX_RULE_FILE_BYTES yet can pack tens of thousands
        // of tiny rules, and matchFirst is a linear scan per accessibility
        // event. Over-cap → typed RuleCompileException → the malformed-file
        // skip below (the platform loads nothing, fail closed behind #432).
        val ruleCount = (root["screens"]?.jsonArray?.size ?: 0) +
            (root["clicks"]?.jsonArray?.size ?: 0) +
            (root["notifications"]?.jsonArray?.size ?: 0)
        if (ruleCount > RuleCompiler.MAX_RULES_PER_FILE) {
            throw RuleCompileException(
                "'$source' declares $ruleCount rules, exceeding " +
                    "MAX_RULES_PER_FILE=${RuleCompiler.MAX_RULES_PER_FILE}",
            )
        }

        // #293 item 8: the file's platform_id (validated present by
        // RulesetLoader.validate) pins each rule id's required platform prefix.
        val platformId = root.getValue("platform_id").jsonPrimitive.content

        val screenArray = root["screens"]?.jsonArray
        val clickArray = root["clicks"]?.jsonArray
        val notificationArray = root["notifications"]?.jsonArray
        val screens = screenArray
            ?.let { RuleCompiler.compileRules<UiNode>(it, RuleContext.SCREEN, platformId) }
            ?: emptyList()
        val clicks = clickArray
            ?.let { RuleCompiler.compileRules<UiNode>(it, RuleContext.CLICK, platformId) }
            ?: emptyList()
        val notifications = notificationArray
            ?.let { RuleCompiler.compileRules<RawNotificationData>(it, RuleContext.NOTIFICATION, platformId) }
            ?: emptyList()

        val formatVersion = root["format_version"]?.jsonPrimitive?.int

        // #624 (VET V4): reject a FILE whose OWN rule ids collide within a rule
        // type. Ruleset.byId is last-wins, so two same-id rules would let the
        // capture-redaction lookup (redactFor/notifRedactFor) resolve to the
        // WRONG rule's block. Skip the offending file per the malformed-file-skip
        // policy — other platforms still load; NEVER throw into Ruleset.init,
        // which would blind ALL sensing behind the #432 fail-closed gate.
        val dupId = firstDuplicateId(screens) ?: firstDuplicateId(clicks) ?: firstDuplicateId(notifications)
        if (dupId != null) {
            Timber.tag("Rules").e(
                "JsonRuleInterpreter: rejected '%s': duplicate rule id '%s' — ids must be unique " +
                    "per rule type (byId redact lookup would resolve to the wrong rule, #624)",
                source, dupId,
            )
            return null
        }

        Timber.tag("Rules").i(
            "JsonRuleInterpreter: compiled '$source' " +
                "(screens=${screens.size}, clicks=${clicks.size}, notifications=${notifications.size})"
        )

        CompiledRuleFile(
            bundle = JsonRuleInterpreter.CompiledRuleBundle(screens, clicks, notifications, formatVersion),
            platformId = platformId,
            rawRules = mapOf(
                RuleContext.SCREEN to rawById(screenArray),
                RuleContext.CLICK to rawById(clickArray),
                RuleContext.NOTIFICATION to rawById(notificationArray),
            ),
            isolatedRuleIds = isolatedIds(screenArray, screens) +
                isolatedIds(clickArray, clicks) +
                isolatedIds(notificationArray, notifications),
        )
    } catch (e: RuleCompileException) {
        Timber.tag("Rules").e(e, "JsonRuleInterpreter: compile error in '$source'")
        null
    } catch (e: Exception) {
        Timber.tag("Rules").e(e, "JsonRuleInterpreter: parse error in '$source'")
        null
    }
}

/** The first rule id that appears more than once in [rules], or null (#624). */
private fun <T> firstDuplicateId(rules: List<CompiledRule<T>>): String? =
    rules.groupingBy { it.id }.eachCount().entries.firstOrNull { it.value > 1 }?.key

private fun rawId(element: kotlinx.serialization.json.JsonElement): String? =
    ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun rawById(array: JsonArray?): Map<String, JsonObject> =
    array.orEmpty().mapNotNull { element -> rawId(element)?.let { it to element.jsonObject } }.toMap()

/**
 * The raw ids the compiler did not return. A rule with an unreadable id never isolates (the
 * #293 belt rejects its file), so every isolated rule has a readable id.
 */
private fun isolatedIds(array: JsonArray?, compiled: List<CompiledRule<*>>): List<String> {
    val remaining = compiled.map { it.id }.toMutableList()
    return array.orEmpty().mapNotNull(::rawId).filterNot { remaining.remove(it) }
}
