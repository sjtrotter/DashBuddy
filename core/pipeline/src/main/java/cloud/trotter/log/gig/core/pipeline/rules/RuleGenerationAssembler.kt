package cloud.trotter.log.gig.core.pipeline.rules

import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.state.Platform
import timber.log.Timber
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale

/**
 * Builds one [RuleGeneration] from bundled and remote rules files, or rejects it whole (#1568).
 *
 * **Bundled files** keep the per-file policy the APK's own rules have always had: a file that fails to
 * compile is skipped, a non-sensitive rule with an authoring error is skipped, and a file that collides
 * with an earlier one is skipped ([acceptNonCollidingFiles]). They are the floor, and nothing older
 * stands behind them.
 *
 * **Remote files** are all or nothing. A remote file that fails to compile, has a rule the compiler
 * isolated, names no known platform, or carries a rule [RemoteRuleAdmission] refuses, rejects the
 * generation, and the live one stays.
 *
 * **Protected rules** are the bundled rules whose every compiled branch has the sensitive shape,
 * decided by shape, never by the id. A remote rule with a protected id is dropped with a WARN and the
 * bundled rule stays: a remote set cannot remove a block. That covers the `sensitive.catchall` rules
 * too, but they are overrideable at a high priority number by design, so any more specific rule,
 * bundled or remote, can still pre-empt them (ADR-0013 X5).
 *
 * **Evaluation groups**, in order: (1) the bundled guard, every bundled `overrideable: false` rule;
 * (2) the remote `overrideable: false` rules; (3) the overrideable rules, remote for each platform a
 * remote file carries and bundled for the rest, plus that platform's protected bundled rules.
 *
 * The #633 id and #438 priority checks then run on the effective generation: a remote rule that
 * collides with a rule already in it is dropped with a WARN, not its file. The #419/#432 coverage
 * check follows: a platform whose screens include remote rules and no sensitive rule rejects the
 * generation; a bundled-only platform without one has its screen rules excluded.
 */
internal object RuleGenerationAssembler {

    fun assemble(files: List<RuleFile>, compile: (RuleFile) -> CompiledRuleFile?): GenerationResult {
        val bundled = mutableListOf<Pair<String, JsonRuleInterpreter.CompiledRuleBundle>>()
        val remote = mutableListOf<RemoteFile>()
        for (file in files) {
            val compiled = compile(file)
            when (file) {
                is RuleFile.Bundled -> if (compiled != null) bundled += file.source to compiled.bundle
                is RuleFile.Remote -> {
                    if (compiled == null) return rejected("remote '${file.source}' failed to compile")
                    if (compiled.isolatedRuleIds.isNotEmpty()) {
                        return rejected(
                            "remote '${file.source}': compile errors isolated rule(s) ${compiled.isolatedRuleIds}",
                        )
                    }
                    val platform = Platform.fromWire(compiled.platformId.substringBefore('.'))
                        ?.takeUnless { it == Platform.Unknown }
                        ?: return rejected("remote '${file.source}': platform_id names no known platform")
                    remote += RemoteFile(file.source, platform, compiled)
                }
            }
        }
        val acceptedBundled = acceptNonCollidingFiles(bundled)
        val bundledRules = acceptedBundled.flatMap { (_, b) -> b.screens + b.clicks + b.notifications }
        val protectedIds = bundledRules.filter { it.isSensitiveShape }.map { it.id }.toSet()

        for (file in remote) {
            for ((context, rules) in file.sections()) {
                for (rule in rules) {
                    if (rule.id in protectedIds) continue
                    val raw = file.compiled.rawRules.getValue(context).getValue(rule.id)
                    RemoteRuleAdmission.refusal(raw, rule, context)
                        ?.let { return rejected("remote '${file.source}': $it") }
                }
            }
        }

        val scope = Scope(remote.map { it.platform }.toSet(), protectedIds)
        val screens = scope.effective(
            "screens",
            acceptedBundled.map { it.second.screens },
            remote.map { it.admitted(RuleContext.SCREEN, it.compiled.bundle.screens, protectedIds) },
        )
        val clicks = scope.effective(
            "clicks",
            acceptedBundled.map { it.second.clicks },
            remote.map { it.admitted(RuleContext.CLICK, it.compiled.bundle.clicks, protectedIds) },
        )
        val notifications = scope.effective(
            "notifications",
            acceptedBundled.map { it.second.notifications },
            remote.map { it.admitted(RuleContext.NOTIFICATION, it.compiled.bundle.notifications, protectedIds) },
        )

        val uncovered = missingSensitivePlatforms(screens.all)
        uncovered.firstOrNull { it in scope.remotePlatforms }?.let {
            return rejected("platform '${it.wire}' ships screen rules but no sensitive rule (#419)")
        }
        val liveScreens = if (uncovered.isEmpty()) screens else {
            Timber.tag("Rules").e(
                "JsonRuleInterpreter: platform(s) %s ship screen rules but NO sensitive rule — " +
                    "excluding their screen rules (fail closed)",
                uncovered.joinToString { it.wire },
            )
            screens.without(uncovered)
        }

        val live = Collections.newSetFromMap(IdentityHashMap<CompiledRule<*>, Boolean>())
        live += liveScreens.all + clicks.all + notifications.all
        fun liveOf(rules: List<CompiledRule<*>>) = rules.filter { it in live }
        val capabilities: List<RuleCapability> =
            acceptedBundled.flatMap { (path, b) ->
                RuleCompiler.enumerateCapabilities(
                    liveOf(b.screens + b.clicks + b.notifications),
                    source = "${RuleCapabilityGrants.ASSET_SOURCE_PREFIX}$path",
                )
            } + remote.flatMap { file ->
                val b = file.compiled.bundle
                RuleCompiler.enumerateCapabilities(liveOf(b.screens + b.clicks + b.notifications), source = file.source)
            }

        val formatVersion = (acceptedBundled.map { it.second.formatVersion } +
            remote.map { it.compiled.bundle.formatVersion }).lastOrNull { it != null }

        return GenerationResult.Prepared(
            generation = RuleGeneration(
                screens = liveScreens.ruleset(),
                clicks = clicks.ruleset(),
                notifications = notifications.ruleset(),
                formatVersion = formatVersion,
            ),
            capabilities = capabilities,
            bundledFiles = acceptedBundled.size,
            remoteFiles = remote.size,
        )
    }

    private fun rejected(reason: String): GenerationResult.Rejected {
        Timber.tag("Rules").e("Rules generation rejected, the live generation stays: %s", reason)
        return GenerationResult.Rejected(reason)
    }

    private class RemoteFile(val source: String, val platform: Platform, val compiled: CompiledRuleFile) {
        fun sections(): List<Pair<RuleContext, List<CompiledRule<*>>>> = listOf(
            RuleContext.SCREEN to compiled.bundle.screens,
            RuleContext.CLICK to compiled.bundle.clicks,
            RuleContext.NOTIFICATION to compiled.bundle.notifications,
        )

        /** [rules] without the protected ids, each drop logged. */
        fun <T> admitted(context: RuleContext, rules: List<CompiledRule<T>>, protectedIds: Set<String>): List<CompiledRule<T>> =
            rules.filter { rule ->
                val keep = rule.id !in protectedIds
                if (!keep) {
                    Timber.tag("Rules").w(
                        "Remote rule '%s' in '%s' has the id of a protected bundled rule — dropped, the bundled " +
                            "rule stays (%s)",
                        rule.id, source, context.name.lowercase(Locale.ROOT),
                    )
                }
                keep
            }
    }

    /** One section of the effective generation: the bundled guard (group 1) and everything after it. */
    private class Section<T>(val guard: List<CompiledRule<T>>, val rules: List<CompiledRule<T>>) {
        val all: List<CompiledRule<T>> get() = guard + rules
        fun ruleset() = Ruleset(rules, guard)
        fun without(platforms: Set<Platform>) = Section(
            guard.filterNot { Platform.fromRuleId(it.id) in platforms },
            rules.filterNot { Platform.fromRuleId(it.id) in platforms },
        )
    }

    private class Scope(val remotePlatforms: Set<Platform>, val protectedIds: Set<String>) {

        fun <T> effective(
            section: String,
            bundledFiles: List<List<CompiledRule<T>>>,
            remoteFiles: List<List<CompiledRule<T>>>,
        ): Section<T> {
            val bundled = bundledFiles.flatten()
            val guard = bundled.filterNot { it.overrideable }
            val keptBundled = bundled.filter {
                it.overrideable && (Platform.fromRuleId(it.id) !in remotePlatforms || it.id in protectedIds)
            }
            val ids = (guard + keptBundled).map { it.id }.toMutableSet()
            val priorities = (guard + keptBundled).map { priorityKey(it) }.toMutableSet()
            val keptRemote = remoteFiles.flatten().filter { rule ->
                val collision = when {
                    rule.id in ids -> "its id"
                    priorityKey(rule) in priorities -> "its priority ${rule.priority}"
                    else -> null
                }
                if (collision != null) {
                    Timber.tag("Rules").w(
                        "Remote %s rule '%s' collides on %s with a rule already in the generation — dropped",
                        section, rule.id, collision,
                    )
                } else {
                    ids += rule.id
                    priorities += priorityKey(rule)
                }
                collision == null
            }
            return Section(guard, keptBundled + keptRemote)
        }

        private fun priorityKey(rule: CompiledRule<*>) = Platform.fromRuleId(rule.id) to rule.priority
    }
}
