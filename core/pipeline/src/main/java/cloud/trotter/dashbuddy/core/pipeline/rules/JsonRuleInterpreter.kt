package cloud.trotter.dashbuddy.core.pipeline.rules

import android.content.Context
import cloud.trotter.dashbuddy.domain.capability.RuleCapability
import cloud.trotter.dashbuddy.domain.capability.RuleCapabilityGrants
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.domain.model.notification.RawNotificationData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
 * A file that re-declares a rule id an earlier-loaded file already claimed is likewise
 * skipped whole ([acceptNonCollidingFiles], #633) so a later file can't shadow the
 * byId redact lookup across files.
 *
 * Security checks applied before parsing:
 * - File size ≤ [MAX_FILE_BYTES] (1 MB) per file
 * - [RuleCompiler.MAX_DEPTH] caps nesting depth during compilation
 * - [RuleCompiler.MAX_REGEX_LENGTH] caps regex patterns during compilation
 *
 * Consent (#417): each load enumerates the action capabilities the bundle's
 * target bindings enable and reconciles them into [RuleCapabilityGrants]
 * BEFORE the compiled rules go live — by the time a frame can classify, the
 * grant store already reflects the bundle (asset sources auto-grant; remote
 * sources never do).
 */
@Singleton
class JsonRuleInterpreter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val capabilityGrants: RuleCapabilityGrants,
) : ScreenRedactionSource {

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

    /**
     * #598: the compiled `redact` block for a recognized screen rule, read off
     * the live bundle (volatile). Null when the rule declares no redaction, so
     * the capture stage skips the tree copy entirely.
     */
    override fun redactFor(ruleId: String): CompiledRedact? =
        current.screens?.ruleById(ruleId)?.redact?.takeUnless { it.isEmpty() }

    /**
     * #620: the compiled notification `redact` block for a recognized notification
     * rule, read off the live bundle (volatile). Null when the rule declares no
     * redaction, so the capture stage skips masking entirely.
     */
    override fun notifRedactFor(ruleId: String): CompiledNotifRedact? =
        current.notifications?.ruleById(ruleId)?.notifRedact?.takeUnless { it.isEmpty() }

    /**
     * True once a ruleset bundle has been published. The pipelines drop (not
     * capture) every frame until this flips (#432): the sensitive-screen gate
     * is rule-driven, so a frame classified before rules load would bypass it
     * and land in the UNKNOWN capture path.
     */
    val isLoaded: Boolean get() = current.screens != null

    private data class LoadedRulesets(
        val screens: Ruleset<UiNode>? = null,
        val clicks: Ruleset<UiNode>? = null,
        val notifications: Ruleset<RawNotificationData>? = null,
        val formatVersion: Int? = null,
    )

    /** Load all bundled rule files from `assets/rules/`. */
    suspend fun loadDefaults() {
        try {
            val files = context.assets.list(RULES_DIR)
                ?.filter { it.endsWith(".json") }
                ?.sorted() // deterministic load order so the #633 first-file-wins collision policy is reproducible (assets.list ordering is not a documented contract)
                ?: emptyList()

            if (files.isEmpty()) {
                Timber.w("JsonRuleInterpreter: no rule files found in $RULES_DIR/")
                return
            }

            val perFile = mutableListOf<Pair<String, CompiledRuleBundle>>() // path → bundle, load order
            for (fileName in files) {
                val path = "$RULES_DIR/$fileName"
                val json = context.assets.open(path).bufferedReader().readText()
                val result = loadSingle(json, source = path) ?: continue
                perFile += path to result
            }

            // Post-merge cross-file collision policy (#633 rule-id + #438 item 3
            // priority): drop any LATER file that collides with an EARLIER file on a
            // rule id OR a (platform, section, priority) tuple, BEFORE the merge.
            // Ruleset.byId is last-wins, so a re-declared id would shadow the
            // capture-redaction lookup (redactFor/notifRedactFor) across files while
            // priority-ordered matchFirst still recognizes with the earlier rule — the
            // wrong rule's redact could resolve and ship an id-keyed customer-PII node
            // raw. A duplicate priority within one platform's section makes matchFirst's
            // tie-break decide recognition by load order silently. Both skip the
            // offending file WHOLE (malformed-file-skip policy), complementing the
            // within-file dup-id (#624) and per-file priority (compileRules) rejects.
            // Earlier files keep every rule; other platforms are unaffected (no outage
            // behind #432).
            val accepted = acceptNonCollidingFiles(perFile)

            val allScreens = mutableListOf<CompiledRule<UiNode>>()
            val allClicks = mutableListOf<CompiledRule<UiNode>>()
            val allNotifications = mutableListOf<CompiledRule<RawNotificationData>>()
            var lastFormatVersion: Int? = null
            for ((_, result) in accepted) {
                allScreens += result.screens
                allClicks += result.clicks
                allNotifications += result.notifications
                result.formatVersion?.let { lastFormatVersion = it }
            }

            // Sensitive-coverage check (#432, complements #419): every platform
            // that ships screen rules MUST ship at least one sensitive rule —
            // the matcher-layer block is only as real as its coverage. A
            // platform without one has its SCREEN rules excluded (fail closed:
            // its frames classify UNKNOWN, where the capture-scrub backstop
            // applies) rather than loaded blind.
            val uncovered = missingSensitivePlatforms(allScreens)
            val effectiveScreens = if (uncovered.isEmpty()) {
                allScreens
            } else {
                Timber.e(
                    "JsonRuleInterpreter: platform(s) %s ship screen rules but NO sensitive rule — " +
                        "excluding their screen rules (fail closed)",
                    uncovered.joinToString { it.wire },
                )
                allScreens.filterNot { Platform.fromRuleId(it.id) in uncovered }
            }

            // Consent reconcile (#417), BEFORE the swap goes live. Enumerated
            // from the EFFECTIVE rules — a platform excluded by the sensitive
            // check must not have its capabilities granted either. Source is
            // stamped per file so provenance survives the merge.
            reconcileCapabilities(
                accepted.flatMap { (path, bundle) ->
                    val liveScreens =
                        bundle.screens.filterNot { Platform.fromRuleId(it.id) in uncovered }
                    RuleCompiler.enumerateCapabilities(
                        liveScreens + bundle.clicks + bundle.notifications,
                        source = "${RuleCapabilityGrants.ASSET_SOURCE_PREFIX}$path",
                    )
                },
            )

            current = LoadedRulesets(
                screens = Ruleset(effectiveScreens),
                clicks = Ruleset(allClicks),
                notifications = Ruleset(allNotifications),
                formatVersion = lastFormatVersion,
            )

            Timber.i(
                "JsonRuleInterpreter: loaded %d file(s) from %s/ " +
                    "(screens=%d, clicks=%d, notifications=%d)",
                accepted.size, RULES_DIR, allScreens.size, allClicks.size, allNotifications.size,
            )
        } catch (e: Exception) {
            Timber.e(e, "JsonRuleInterpreter: failed to load rules directory")
        }
    }

    /**
     * Parse and compile a single rules JSON string.
     * Used by [loadDefaults] and will be used by the CDN fetch path in Phase A3+.
     *
     * `internal` (#416 review F4): compiling raw JSON is a module-internal step — the
     * only public entry points are [loadDefaults] (bundled assets, APK-signature covered)
     * and [load] (which takes a signature-verified [VerifiedRulesetBytes]). Reachable from
     * `:core:pipeline` tests (same module).
     *
     * @return compiled rule lists, or null if validation/compilation fails.
     */
    internal fun loadSingle(jsonString: String, source: String = "unknown"): CompiledRuleBundle? {
        if (jsonString.length > MAX_FILE_BYTES) {
            Timber.e("JsonRuleInterpreter: $source exceeds size limit (${jsonString.length} bytes)")
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
                Timber.e("JsonRuleInterpreter: rejected '$source': $rejection")
                return null
            }

            // #419: cap the total rule count from the RAW arrays, BEFORE compiling
            // — a 1 MB bundle passes MAX_FILE_BYTES yet can pack tens of thousands
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
            val platformId = root["platform_id"]?.jsonPrimitive?.content

            val screens = root["screens"]?.jsonArray
                ?.let { RuleCompiler.compileRules<UiNode>(it, RuleContext.SCREEN, platformId) }
                ?: emptyList()
            val clicks = root["clicks"]?.jsonArray
                ?.let { RuleCompiler.compileRules<UiNode>(it, RuleContext.CLICK, platformId) }
                ?: emptyList()
            val notifications = root["notifications"]?.jsonArray
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
                Timber.e(
                    "JsonRuleInterpreter: rejected '%s': duplicate rule id '%s' — ids must be unique " +
                        "per rule type (byId redact lookup would resolve to the wrong rule, #624)",
                    source, dupId,
                )
                return null
            }

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
     * Load a single **signature-verified** ruleset and replace all current rules
     * (the CDN hot-reload / side-load path, #192).
     *
     * Fail-closed BY CONSTRUCTION (#416): this path only accepts a
     * [VerifiedRulesetBytes], which [RulesetVerifier] mints ONLY after a detached
     * signature over the exact bundle bytes verifies against the configured source's
     * pinned public key. There is no `load(String)` overload — an unverified remote
     * bundle cannot reach compile. (Bundled asset rulesets skip this: they load via
     * [loadDefaults], already covered by the APK signature.)
     *
     * The verified [VerifiedRulesetBytes.source] is NOT asset-prefixed, so nothing a
     * remote caller loads is ever auto-granted (#417) — its capabilities reconcile
     * as pending.
     */
    suspend fun load(verified: VerifiedRulesetBytes) {
        val jsonString = verified.json
        val source = verified.source
        val result = loadSingle(jsonString, source) ?: return

        // #419 (2a): a REPLACEMENT bundle must not silently drop sensitive-screen
        // coverage. loadDefaults merges independent platform files, so it EXCLUDES
        // only an uncovered platform's screens; load() swaps the WHOLE ruleset
        // atomically, so a coverage downgrade must not go live at all — REJECT and
        // keep the previous good bundle (its sensitive rules stay in force). Uses
        // the same missingSensitivePlatforms SSOT as the #432 loadDefaults gate.
        val uncovered = missingSensitivePlatforms(result.screens)
        if (uncovered.isNotEmpty()) {
            Timber.e(
                "JsonRuleInterpreter: rejected replacement '%s' — platform(s) %s ship screen rules " +
                    "but NO sensitive rule; keeping the previous bundle (fail closed, #419/#432)",
                source, uncovered.joinToString { it.wire },
            )
            return
        }

        reconcileCapabilities(
            RuleCompiler.enumerateCapabilities(
                result.screens + result.clicks + result.notifications,
                source = source,
            ),
        )
        current = LoadedRulesets(
            screens = Ruleset(result.screens),
            clicks = Ruleset(result.clicks),
            notifications = Ruleset(result.notifications),
            formatVersion = result.formatVersion,
        )
    }

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
            Timber.e(e, "JsonRuleInterpreter: capability reconcile failed — automation taps stay denied")
        }
    }

    /** The first rule id that appears more than once in [rules], or null (#624). */
    private fun <T> firstDuplicateId(rules: List<CompiledRule<T>>): String? =
        rules.groupingBy { it.id }.eachCount().entries.firstOrNull { it.value > 1 }?.key

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
            rules.none { r -> r.branches.any { it.shape == "sensitive" } }
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
 * ([JsonRuleInterpreter.redactFor] / [JsonRuleInterpreter.notifRedactFor]) the capture
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
            Timber.e(
                "JsonRuleInterpreter: rule id '%s' in '%s' already claimed by '%s' — skipping the " +
                    "later file (cross-file byId redact-lookup shadow, #633)",
                idCollision, path, claimedBy.getValue(idCollision),
            )
            continue
        }
        val priorityKeys = bundlePriorityKeys(bundle)
        val priorityCollision = priorityKeys.firstOrNull { it in priorityClaimedBy }
        if (priorityCollision != null) {
            Timber.e(
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
