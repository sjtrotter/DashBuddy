package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData

/**
 * One complete, compiled rule set (#1568): the screen, click and notification rulesets that go live
 * together and are replaced together. Immutable, so the generation that classified a frame can be
 * carried to the capture stage and mask that frame with its own rules, whatever went live since.
 */
class RuleGeneration(
    val screens: Ruleset<UiNode> = Ruleset(emptyList()),
    val clicks: Ruleset<UiNode> = Ruleset(emptyList()),
    val notifications: Ruleset<RawNotificationData> = Ruleset(emptyList()),
    val formatVersion: Int? = null,
) : ScreenRedactionSource {

    /** The compiled `redact` block of screen rule [ruleId] in this generation, or null when it declares none. */
    override fun redactFor(ruleId: String): CompiledRedact? =
        screens.ruleById(ruleId)?.redact?.takeUnless { it.isEmpty() }

    /** The compiled notification `redact` block of [ruleId] in this generation, or null when it declares none. */
    override fun notifRedactFor(ruleId: String): CompiledNotifRedact? =
        notifications.ruleById(ruleId)?.notifRedact?.takeUnless { it.isEmpty() }

    companion object {
        /** No rules: the redaction source of a frame no generation classified. */
        val EMPTY = RuleGeneration()
    }
}

/** One rules file of a generation: bundled (the APK's own) or remote (signature-verified). */
sealed class RuleFile(val source: String, internal val json: String) {

    /** A file shipped in the APK's `assets/rules/`; only the interpreter reads these. */
    class Bundled internal constructor(source: String, json: String) : RuleFile(source, json)

    /** A downloaded file; only a passing signature check can produce its [VerifiedRulesetBytes]. */
    class Remote(verified: VerifiedRulesetBytes) : RuleFile(verified.source, verified.json)
}

/** The outcome of [JsonRuleInterpreter.prepareGeneration]. */
sealed interface GenerationResult {

    /** A generation that passed every check; [JsonRuleInterpreter.activate] makes it live. */
    class Prepared internal constructor(
        val generation: RuleGeneration,
        internal val capabilities: List<RuleCapability>,
        val bundledFiles: Int,
        val remoteFiles: Int,
    ) : GenerationResult

    /** A generation that failed a check. The live generation stays. [reason] names files and rule ids only. */
    data class Rejected(val reason: String) : GenerationResult
}
