package cloud.trotter.log.gig.core.pipeline.rules

import android.content.Context
import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that owns the live rule generation (#1568).
 *
 * Every load goes through one path: a generation is compiled and checked whole
 * ([RuleGenerationAssembler] holds the rules) without touching the live one, then made live in
 * one step. [loadDefaults] loads the bundled `assets/rules/` files, which become the trusted
 * bundled floor; [prepareGeneration] builds every remote candidate on that floor, so a caller
 * cannot drop the bundled blocks by leaving them out, and it refuses until the floor exists.
 * Preparing, reconciling capabilities and publishing run under one lock, so two loads cannot
 * leave one generation live with the other's capabilities.
 *
 * Security checks applied before parsing, per file:
 * - File size ≤ [MAX_RULE_FILE_BYTES] (1 MB)
 * - [RuleCompiler.MAX_DEPTH] caps nesting depth during compilation
 * - [RuleCompiler.MAX_REGEX_LENGTH] caps regex patterns during compilation
 *
 * Consent (#417, #843): activation reconciles the generation's action capabilities into
 * [RuleCapabilityGrants] BEFORE the generation goes live. Reconcile grants nothing; every
 * capability, bundled or remote, lands undecided until the dasher answers.
 */
@Singleton
class JsonRuleInterpreter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilityGrants: RuleCapabilityGrants,
) {

    /**
     * The live generation behind a single volatile reference (#361): a reader sees the previous
     * complete generation or the next, never a mix. A reader that needs more than one ruleset, or
     * a ruleset and a redaction, reads [generation] once and keeps that object.
     */
    @Volatile
    private var current: RuleGeneration? = null

    /** The bundled files of the live bundled generation, the floor every remote candidate is built on. */
    @Volatile
    private var bundledFloor: List<RuleFile.Bundled>? = null

    /** Held across prepare, reconcile and publish. */
    private val activation = Mutex()

    /** Counts publishes. Written after [bundledFloor], read before it. */
    @Volatile
    private var revision = 0L

    val generation: RuleGeneration? get() = current
    val screenRuleset: Ruleset<UiNode>? get() = current?.screens
    val clickRuleset: Ruleset<UiNode>? get() = current?.clicks
    val notificationRuleset: Ruleset<RawNotificationData>? get() = current?.notifications
    val loadedFormatVersion: Int? get() = current?.formatVersion

    /**
     * True once a generation is live. The pipelines drop (not capture) every frame until this
     * flips (#432): the sensitive-screen gate is rule-driven, so a frame classified before rules
     * load would bypass it and land in the UNKNOWN capture path.
     */
    val isLoaded: Boolean get() = current != null

    /** Load all bundled rule files from `assets/rules/`. */
    suspend fun loadDefaults() {
        val files = try {
            context.assets.list(RULES_DIR)
                ?.filter { it.endsWith(".json") }
                ?.sorted() // deterministic load order so the #633 first-file-wins collision policy is reproducible (assets.list ordering is not a documented contract)
                ?.map { name ->
                    val path = "$RULES_DIR/$name"
                    RuleFile.Bundled(path, context.assets.open(path).bufferedReader().use { it.readText() })
                }
                ?: emptyList()
        } catch (e: Exception) {
            Timber.tag("Rules").e(e, "JsonRuleInterpreter: failed to read the rules directory")
            return
        }
        if (files.isEmpty()) {
            Timber.tag("Rules").w("JsonRuleInterpreter: no rule files found in $RULES_DIR/")
            return
        }
        loadBundled(files)
    }

    /** Prepare [files] as a bundled-only generation; when it goes live, they become the bundled floor. */
    internal suspend fun loadBundled(files: List<RuleFile.Bundled>) {
        activation.withLock {
            val result = RuleGenerationAssembler.assemble(files, emptyList())
            if (result is GenerationResult.Prepared) {
                bundledFloor = files
                publish(result)
            }
        }
    }

    /**
     * Prepare one **signature-verified** remote file on the bundled floor and make the result live
     * (the side-load path today; the rules feed, #640, later).
     *
     * Fail-closed BY CONSTRUCTION (#416): a remote file is a [RuleFile.Remote], built only from a
     * [VerifiedRulesetBytes], which [RulesetVerifier] mints ONLY after a detached signature over the
     * exact bytes verifies against the configured source's pinned public key. Bundled asset files
     * skip this: they load via [loadDefaults], already covered by the APK signature.
     */
    suspend fun load(verified: VerifiedRulesetBytes) {
        activation.withLock {
            val result = prepareOnFloor(listOf(RuleFile.Remote(verified)))
            if (result is GenerationResult.Prepared) publish(result)
        }
    }

    /**
     * Compile and check [remote] on the bundled floor as one generation, without touching the live
     * one. Rejected before [loadDefaults] has made a bundled generation live, and when a file fails
     * any check; see [RuleGenerationAssembler] for the whole list.
     */
    fun prepareGeneration(remote: List<RuleFile.Remote>): GenerationResult = prepareOnFloor(remote)

    /**
     * Reconcile [prepared]'s capabilities, then make its generation live. Refused (false, live
     * generation untouched) when [prepared] came from another interpreter, or a generation went live
     * since it was prepared: it was built on a floor or a live state that is no longer current.
     */
    suspend fun activate(prepared: GenerationResult.Prepared): Boolean = activation.withLock {
        val fresh = prepared.owner === this && prepared.baseRevision == revision &&
            prepared.floor === bundledFloor
        if (fresh) {
            publish(prepared)
        } else {
            Timber.tag("Rules").w("Rules generation refused at activation: prepared on a generation no longer live")
        }
        fresh
    }

    private fun prepareOnFloor(remote: List<RuleFile.Remote>): GenerationResult {
        val base = revision
        val floor = bundledFloor
        if (floor == null) {
            Timber.tag("Rules").e("Rules generation rejected: no bundled rules are live yet")
            return GenerationResult.Rejected("no bundled rules are live yet")
        }
        return when (val result = RuleGenerationAssembler.assemble(floor, remote)) {
            is GenerationResult.Prepared -> result.stamped(this, floor, base)
            is GenerationResult.Rejected -> result
        }
    }

    private suspend fun publish(prepared: GenerationResult.Prepared) {
        reconcileCapabilities(prepared.capabilities)
        current = prepared.generation
        revision++
        Timber.tag("Rules").i(
            "Rules generation live: %d bundled + %d remote file(s) (screens=%d, clicks=%d, notifications=%d)",
            prepared.bundledFiles, prepared.remoteFiles, prepared.generation.screens.ruleCount,
            prepared.generation.clicks.ruleCount, prepared.generation.notifications.ruleCount,
        )
    }

    /**
     * Parse and compile a single rules JSON string, for tests of the per-file checks.
     *
     * @return compiled rule lists, or null if validation/compilation fails.
     */
    internal fun loadSingle(jsonString: String, source: String = "unknown"): CompiledRuleBundle? =
        compileRuleFile(jsonString, source)?.bundle

    /**
     * Reconcile failure degrades ACTUATION only, never recognition: the gate
     * denies ungranted actions (fail closed) while the rules still load and
     * classify. Throwing here would instead kill the whole rule load — a
     * datastore hiccup must not blind the app.
     */
    private suspend fun reconcileCapabilities(capabilities: List<RuleCapability>) {
        try {
            capabilityGrants.reconcile(capabilities)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag("Rules").e(e, "JsonRuleInterpreter: capability reconcile failed — automation taps stay denied")
        }
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
    }
}

/**
 * Platforms whose screen-rule set lacks ANY sensitive-shaped branch
 * (`parse.as == "sensitive"`) — #432. Pure and top-level so it's testable
 * without an Android Context (public: the app-module integration test pins
 * that the shipped bundles never trip it).
 */
fun missingSensitivePlatforms(screens: List<CompiledRule<UiNode>>): Set<Platform> {
    val byPlatform = screens.groupBy { Platform.fromRuleId(it.id) }
    return byPlatform.filterKeys { it != Platform.Unknown }
        .filterValues { rules ->
            rules.none { r -> r.branches.any { it.shape == SENSITIVE_SHAPE } }
        }
        .keys
}

/**
 * A merged-rule priority uniqueness key (#438 item 3): a duplicate priority is a
 * conflict only WITHIN one platform's one rule section. Distinct platforms may reuse
 * priorities freely (their rulesets never interleave in [Ruleset.matchFirst]), and a
 * screen and a click may share a priority (separate rulesets).
 */
private data class PriorityKey(val platform: Platform, val section: String, val priority: Int)

private fun priorityKey(rule: CompiledRule<*>, section: String) =
    PriorityKey(Platform.fromRuleId(rule.id), section, rule.priority)

/**
 * Post-merge cross-file collision policy (#633 rule id + #438 item 3 priority), applied per rule
 * since #1568. Given per-file compiled bundles in LOAD ORDER, drop each rule that collides with a
 * rule an EARLIER file kept, on EITHER:
 *  - a top-level rule id ([CompiledRule.id]), or
 *  - a (platform, section, priority) tuple — priority uniqueness enforced POST-MERGE per
 *    (platform, screens|clicks|notifications), not just per file.
 * The colliding rule is dropped with a WARN; the rest of its file loads. Before #1568 the whole
 * later file was skipped, so one duplicate could take a platform's sensitive guard with it.
 *
 * Why per (platform, section) post-merge: [RuleCompiler.compileRules] enforces priority
 * uniqueness per rule TYPE within a SINGLE file. Two files for one platform could each declare
 * `doordash.screen` priority 100, and [Ruleset.matchFirst]'s stable-sort tie-break would then
 * decide recognition by load order, silently.
 *
 * Why the LATER rule loses: [Ruleset]'s `byId` is last-wins, so a later rule re-declaring an id
 * would SHADOW the byId redact lookup ([RuleGeneration.redactFor] / [RuleGeneration.notifRedactFor])
 * while priority-ordered [Ruleset.matchFirst] still recognizes with the earlier rule — the wrong
 * rule's `redact` block would resolve. Keeping the earlier rule makes byId and matchFirst agree.
 *
 * ids come from top-level [CompiledRule.id] only — branches share the parent id. Both checks are
 * a no-op for today's prefix-namespaced single-file-per-platform asset bundles. The WARN names the
 * colliding id/priority and both file paths ONLY — no rule text, no PII (Principle 7).
 *
 * @param files compiled bundles in LOAD ORDER (earlier entries win a collision).
 */
fun dropCollidingRules(
    files: List<Pair<String, JsonRuleInterpreter.CompiledRuleBundle>>,
): List<Pair<String, JsonRuleInterpreter.CompiledRuleBundle>> {
    val idClaimedBy = HashMap<String, String>() // ruleId → first-claiming file path
    val priorityClaimedBy = HashMap<PriorityKey, String>() // (platform, section, priority) → path
    return files.map { (path, bundle) ->
        fun <T> kept(rules: List<CompiledRule<T>>, section: String) = rules.filter { rule ->
            val earlier = idClaimedBy[rule.id] ?: priorityClaimedBy[priorityKey(rule, section)]
            if (earlier != null) {
                Timber.tag("Rules").w(
                    "JsonRuleInterpreter: %s rule '%s' (priority %d) in '%s' collides with a rule '%s' " +
                        "already loaded — dropped, the rest of the file loads (#633/#438)",
                    section, rule.id, rule.priority, path, earlier,
                )
            }
            earlier == null
        }
        val result = bundle.copy(
            screens = kept(bundle.screens, "screens"),
            clicks = kept(bundle.clicks, "clicks"),
            notifications = kept(bundle.notifications, "notifications"),
        )
        for ((section, rules) in listOf("screens" to result.screens, "clicks" to result.clicks,
            "notifications" to result.notifications)) {
            for (rule in rules) {
                idClaimedBy[rule.id] = path
                priorityClaimedBy[priorityKey(rule, section)] = path
            }
        }
        path to result
    }
}
