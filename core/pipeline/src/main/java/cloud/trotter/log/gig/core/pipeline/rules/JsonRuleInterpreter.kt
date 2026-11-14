package cloud.trotter.log.gig.core.pipeline.rules

import android.content.Context
import cloud.trotter.log.gig.domain.capability.RuleCapability
import cloud.trotter.log.gig.domain.capability.RuleCapabilityGrants
import cloud.trotter.log.gig.domain.model.accessibility.UiNode
import cloud.trotter.log.gig.domain.state.Platform
import cloud.trotter.log.gig.domain.model.notification.RawNotificationData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton that owns the live rule generation (#1568).
 *
 * Every load goes through one path: [prepareGeneration] compiles and checks a whole generation
 * ([RuleGenerationAssembler] holds the rules) without touching the live one, and [activate] makes a
 * prepared generation live in one step. [loadDefaults] prepares the bundled `assets/rules/` files;
 * [load] prepares the bundled files plus one signature-verified remote file. A rejected generation
 * leaves the live one in place.
 *
 * Security checks applied before parsing, per file:
 * - File size ≤ [MAX_RULE_FILE_BYTES] (1 MB)
 * - [RuleCompiler.MAX_DEPTH] caps nesting depth during compilation
 * - [RuleCompiler.MAX_REGEX_LENGTH] caps regex patterns during compilation
 *
 * Consent (#417, #843): [activate] reconciles the generation's action capabilities into
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

    /** The bundled files [loadDefaults] read, which every remote generation is built on. */
    @Volatile
    private var bundledFiles: List<RuleFile.Bundled> = emptyList()

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
        bundledFiles = files
        prepareAndActivate(files)
    }

    /**
     * Prepare the bundled files plus one **signature-verified** remote file and make the result
     * live (the side-load path today; the rules feed, #640, later).
     *
     * Fail-closed BY CONSTRUCTION (#416): a remote file is a [RuleFile.Remote], built only from a
     * [VerifiedRulesetBytes], which [RulesetVerifier] mints ONLY after a detached signature over the
     * exact bytes verifies against the configured source's pinned public key. Bundled asset files
     * skip this: they load via [loadDefaults], already covered by the APK signature.
     */
    suspend fun load(verified: VerifiedRulesetBytes) {
        prepareAndActivate(bundledFiles + RuleFile.Remote(verified))
    }

    /**
     * Compile and check [files] as one generation, without touching the live one. Rejected when a
     * remote file fails any check; see [RuleGenerationAssembler] for the whole list.
     */
    fun prepareGeneration(files: List<RuleFile>): GenerationResult =
        RuleGenerationAssembler.assemble(files) { compileRuleFile(it.json, it.source) }

    /** Reconcile [prepared]'s capabilities, then make its generation live. */
    suspend fun activate(prepared: GenerationResult.Prepared) {
        reconcileCapabilities(prepared.capabilities)
        current = prepared.generation
        Timber.tag("Rules").i(
            "Rules generation live: %d bundled + %d remote file(s) (screens=%d, clicks=%d, notifications=%d)",
            prepared.bundledFiles, prepared.remoteFiles, prepared.generation.screens.ruleCount,
            prepared.generation.clicks.ruleCount, prepared.generation.notifications.ruleCount,
        )
    }

    private suspend fun prepareAndActivate(files: List<RuleFile>) {
        val result = prepareGeneration(files)
        if (result is GenerationResult.Prepared) activate(result)
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

private fun bundlePriorityKeys(bundle: JsonRuleInterpreter.CompiledRuleBundle): List<PriorityKey> =
    bundle.screens.map { PriorityKey(Platform.fromRuleId(it.id), "screens", it.priority) } +
        bundle.clicks.map { PriorityKey(Platform.fromRuleId(it.id), "clicks", it.priority) } +
        bundle.notifications.map { PriorityKey(Platform.fromRuleId(it.id), "notifications", it.priority) }

/**
 * Post-merge cross-file collision policy (#633 rule-id + #438 item 3 priority). Given
 * per-file compiled bundles in LOAD ORDER, return only the files that don't collide with
 * an EARLIER-accepted file on EITHER:
 *  - a top-level rule id ([CompiledRule.id]), or
 *  - a (platform, section, priority) tuple — priority uniqueness enforced POST-MERGE per
 *    (platform, screens|clicks|notifications), not just per file.
 * A colliding file is SKIPPED WHOLE and logged at ERROR (lost coverage), never merged.
 *
 * Why per (platform, section) post-merge: [RuleCompiler.compileRules] enforces priority
 * uniqueness per rule TYPE within a SINGLE file, which is sufficient only because today
 * each platform lives in exactly one asset file. The moment a platform splits across
 * files (the #639 subrepo runtime path) or a remote OTA file ships alongside a bundled
 * one (#192/#416), two files could each declare `doordash.screen` priority 100 and the
 * per-file check would never see it — [Ruleset.matchFirst]'s stable-sort tie-break would
 * then decide recognition by load order, silently. This is the post-merge analog of the
 * canonicalize-time dup check (#639).
 *
 * Why skip the LATER file, not the earlier one (both collision kinds): consistent with
 * the #633 first-file-wins policy. For a rule id, [Ruleset]'s `byId` is last-wins, so a
 * later file re-declaring an id would SHADOW the byId redact lookup
 * ([RuleGeneration.redactFor] / [RuleGeneration.notifRedactFor]) the capture
 * stage uses while priority-ordered [Ruleset.matchFirst] still recognizes with a
 * DIFFERENT rule — the wrong rule's `redact` block resolves and an id-keyed customer-PII
 * node (no marker prefix, invisible to the #624 screen backstop) could ship raw. Keeping
 * the earlier file's rules makes byId and matchFirst agree.
 *
 * ids come from top-level [CompiledRule.id] only — branches share the parent id and are
 * NOT in `byId`, so a branch never triggers a collision. Both checks are impossible with
 * the current prefix-namespaced single-file-per-platform asset bundles (`doordash.` /
 * `uber.`), so this is a no-op today (byte-inert; [DefaultRulesIntegrationTest] and
 * [ParseOutputGoldenTest] stay green); it hardens the future multi-file subrepo (#639) +
 * untrusted CDN/fork rule path (#192/#416).
 *
 * Pure + top-level so it's unit-testable without an Android Context. The ERROR log names
 * the colliding id/priority + both file paths ONLY — no rule text / no PII (Principle 7).
 *
 * @param files compiled bundles in LOAD ORDER (earlier entries win a collision).
 */
fun acceptNonCollidingFiles(
    files: List<Pair<String, JsonRuleInterpreter.CompiledRuleBundle>>,
): List<Pair<String, JsonRuleInterpreter.CompiledRuleBundle>> {
    val claimedBy = HashMap<String, String>() // ruleId → first-claiming file path
    val priorityClaimedBy = HashMap<PriorityKey, String>() // (platform, section, priority) → path
    val accepted = mutableListOf<Pair<String, JsonRuleInterpreter.CompiledRuleBundle>>()
    for ((path, bundle) in files) {
        val ids = bundle.screens.map { it.id } +
            bundle.clicks.map { it.id } +
            bundle.notifications.map { it.id }
        val idCollision = ids.firstOrNull { it in claimedBy }
        if (idCollision != null) {
            Timber.tag("Rules").e(
                "JsonRuleInterpreter: rule id '%s' in '%s' already claimed by '%s' — skipping the " +
                    "later file (cross-file byId redact-lookup shadow, #633)",
                idCollision, path, claimedBy.getValue(idCollision),
            )
            continue
        }
        val priorityKeys = bundlePriorityKeys(bundle)
        val priorityCollision = priorityKeys.firstOrNull { it in priorityClaimedBy }
        if (priorityCollision != null) {
            Timber.tag("Rules").e(
                "JsonRuleInterpreter: %s priority %d for platform '%s' in '%s' already claimed by " +
                    "'%s' — skipping the later file (post-merge priority uniqueness, #438)",
                priorityCollision.section, priorityCollision.priority,
                priorityCollision.platform.wire, path, priorityClaimedBy.getValue(priorityCollision),
            )
            continue
        }
        for (id in ids) claimedBy[id] = path
        for (key in priorityKeys) priorityClaimedBy[key] = path
        accepted += path to bundle
    }
    return accepted
}
