package cloud.trotter.dashbuddy.census
import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.LetterRuns
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import cloud.trotter.dashbuddy.core.pipeline.census.diagnostics.DiagnosticSkeletonBuilder
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.census.contract.CensusHash
import cloud.trotter.census.contract.ClassNameGrammar
import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.census.contract.UiSkeletonDto
import cloud.trotter.census.contract.UiSkeletonNodeDto
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
import cloud.trotter.dashbuddy.domain.privacy.MaskTokens
import cloud.trotter.dashbuddy.domain.privacy.PiiShapes
import cloud.trotter.dashbuddy.domain.state.Platform
import cloud.trotter.dashbuddy.test.util.CorpusDecoys
import cloud.trotter.dashbuddy.test.util.PropSeeds
import cloud.trotter.dashbuddy.test.util.SnapshotRedactor
import cloud.trotter.dashbuddy.test.util.TestResourceLoader
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Shared corpus loading and mirrors for the #1145 census corpus tests (split by purpose, #1160 review UU10):
 * [SkeletonCorpusTest] asserts the ADR-0011 §7 invariants (a)–(f) and the seeded property;
 * [SkeletonCorpusGuardsTest] holds the guards (frame-rule flip explanation, chrome recall, id/class pins).
 */
abstract class SkeletonCorpusTestBase {

    companion object {
        /** #878 pinned seed — pins PR CI. Bump deliberately to explore new samples. */
        const val SEED = 0x1145_0001L

        val DAY: LocalDate = LocalDate.of(2026, 9, 30)

        val META = ReplayMetadata(
            engineVersion = 1,
            rulesetFormatVersion = 1,
            rulesetReleaseTag = "corpus",
            appVersion = "test",
            deviceFingerprint = "google/panther/panther:16/NEVER_IN_A_SKELETON",
            platformAppVersion = "0.0.0",
        )

        /** The §7(a) allowlist — per node, per slot, per envelope (ReplayMetadata names + census-own). */
        val NODE_KEYS = setOf("class", "id", "isClickable", "isEnabled", "isChecked", "text", "children")
        val NODE_STRING_KEYS = setOf("class", "id")
        val SLOT_KEYS = setOf("h", "kind")
        val ENVELOPE_REPLAY_METADATA = setOf(
            "platformAppVersion", "appVersion", "rulesetReleaseTag", "engineVersion", "rulesetFormatVersion",
        )
        val ENVELOPE_CENSUS_OWN = setOf("schemaId", "fingerprint", "platform", "day", "filterRev", "hashDomain")
        val ENVELOPE_KEYS = ENVELOPE_REPLAY_METADATA + ENVELOPE_CENSUS_OWN + setOf("windowTitle", "root")

        /** CorpusDecoys entries that are retained CHROME labels, legitimately hashed (ADR §7e). */
        val CHROME_DECOYS = setOf("Hand it to me: ")

        // AJ5: the runtime mask prefix comes from its one owner (`MaskTokens.REDACTED_PREFIX`).
        val MASK_TOKEN = Regex(
            Regex.escape(MaskTokens.REDACTED_PREFIX) + """(?::[0-9a-f]{4})?\]|\[(?:address|email|phone|card|note|name)\]""",
        )

        private val snapshotsDir = File("src/test/resources/snapshots")

        /** Every committed corpus tree, loaded strictly once per test JVM and shared by all test instances. */
        private val corpus: List<Fixture> by lazy {
            val dirs = snapshotsDir.walkTopDown()
                .filter { it.isDirectory && it != snapshotsDir }
                .filter { dir ->
                    val rel = dir.relativeTo(snapshotsDir).invariantSeparatorsPath
                    // Staging (never committed): all of INBOX/, and the flat UNKNOWN/ — but NOT the
                    // committed UNKNOWN/negative/ below it.
                    rel.substringBefore('/') != "INBOX" && rel != "UNKNOWN"
                }
                .toList()
            dirs.flatMap { dir ->
                dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().sorted().mapNotNull { file ->
                    val rel = file.relativeTo(snapshotsDir).invariantSeparatorsPath
                    val root = Json.parseToJsonElement(file.readText()).jsonObject
                    loadTree(file, root)?.let { Fixture(rel, it, (root["schemaId"] as? JsonPrimitive)?.content) }
                }
            }
        }

        /**
         * A screen envelope / bare tree / legacy wrapper decodes to its tree; a CLICK envelope to its
         * clicked node (a tree too); a NOTIFICATION envelope has no tree and is skipped here.
         * NotificationCorpus loads notifications separately through their strict schema (ADR §10).
         */
        private fun loadTree(file: File, root: JsonObject = Json.parseToJsonElement(file.readText()).jsonObject): UiNode? {
            val payload = root["payload"] as? JsonObject
            return when {
                payload == null -> TestResourceLoader.loadNode(file)
                payload.containsKey("node") -> TestResourceLoader.nodeFromElement(payload.getValue("node"))
                payload.containsKey("packageName") || payload.containsKey("channelId") -> null
                else -> TestResourceLoader.loadNode(file)
            }
        }

        /**
         * The platform wire from the capture-naming token `<timestamp>__<platformWire>__…` (#1160 review
         * AA8), resolved through the `Platform` registry — never a literal. Legacy names carry no platform
         * token (a single segment, or an UPPER-CASE screen label in that slot) and resolve to
         * [Platform.Unknown]; a lowercase token that is not a registered wire FAILS LOUD.
         */
        private fun platformOf(path: String): Platform {
            val segments = path.substringAfterLast('/').removeSuffix(".json").split("__")
            val token = segments.getOrNull(1)?.takeIf { segments.size >= 3 && it.matches(Regex("[a-z0-9_]+")) }
                ?: return Platform.Unknown
            return Platform.fromWire(token) ?: error("$path: unknown platform wire '$token'")
        }

        /** ONE build per fixture (review EE5); [builtJson] reuses the JSON the builder already measured. */
        private val outcomes: List<Pair<Fixture, SkeletonBuilder.Outcome>> by lazy {
            corpus.map { it to SkeletonBuilder.outcome(it.tree, null, META, platformOf(it.path), DAY) }
        }

        private val built: List<Pair<Fixture, UiSkeletonDto?>> by lazy {
            outcomes.map { (f, o) -> f to (o as? SkeletonBuilder.Outcome.Built)?.skeleton }
        }

        private val builtJson: Map<String, String> by lazy {
            outcomes.mapNotNull { (f, o) -> (o as? SkeletonBuilder.Outcome.Built)?.let { f.path to it.json } }.toMap()
        }
    }

    protected data class Fixture(val path: String, val tree: UiNode, val sourceSchema: String? = null)

    protected val snapshotsDir: File get() = Companion.snapshotsDir

    protected val corpus: List<Fixture> get() = Companion.corpus

    protected fun loadTree(file: File): UiNode? = Companion.loadTree(file)

    protected fun platformOf(path: String): Platform = Companion.platformOf(path)

    protected fun build(f: Fixture, tree: UiNode = f.tree): UiSkeletonDto? =
        SkeletonBuilder.build(tree, null, META, platformOf(f.path), DAY)

    protected val outcomes: List<Pair<Fixture, SkeletonBuilder.Outcome>> get() = Companion.outcomes

    protected val built: List<Pair<Fixture, UiSkeletonDto?>> get() = Companion.built

    protected val builtJson: Map<String, String> get() = Companion.builtJson

    /** One value's slot through the whole builder (a one-node frame); null when the frame is refused. */
    protected fun slotOf(value: String): TextSlot? =
        SkeletonBuilder.build(UiNode(className = "android.widget.TextView", text = value), null, META, Platform.DoorDash, DAY)
            ?.root?.text?.get("text")

    /**
     * `CensusHash.canonical` (review SS8: nullable — no fixed point). A value with no canonical form is
     * withheld by the builder, so for the mirrors it falls back to its trimmed form (never seeded).
     */
    protected fun canon(value: String): String = CensusHash.canonical(value) ?: value.trim()

    /**
     * Review AC4: a NAME node's run source through the builder's OWN rule (`SkeletonBuilder.nameRunSource`)
     * over the builder's canonical forms — a non-convergent or blank value is unusable, never the trimmed
     * fallback [canon] uses for keys.
     */
    protected fun nameRunSourceOf(n: UiNode): String? =
        SkeletonBuilder.nameRunSource(SkeletonBuilder.seedCanonicalOf(n.text), SkeletonBuilder.seedCanonicalOf(n.contentDescription))

    protected fun walkNodes(node: UiNode, visit: (UiNode) -> Unit) {
        visit(node)
        node.children.forEach { walkNodes(it, visit) }
    }

    protected fun walkSkeleton(node: UiSkeletonNodeDto, visit: (UiSkeletonNodeDto) -> Unit) {
        visit(node)
        node.children.forEach { walkSkeleton(it, visit) }
    }

    protected fun allHashes(item: UiSkeletonDto): List<String> {
        val out = mutableListOf<String>()
        item.windowTitle?.h?.let(out::add)
        walkSkeleton(item.root) { n -> n.text.values.mapNotNullTo(out) { it.h } }
        return out
    }

    /** Rewrite every scrubbable string of every node (the #835 SSOT), keeping structure. */
    protected fun mapTree(node: UiNode, transform: (String?) -> String?): UiNode =
        node.mapScrubbableStrings(transform).copy(children = node.children.map { mapTree(it, transform) })

    /**
     * Reviews OO2 + QQ1: build [tree] with the frame-level rule ON and OFF (the diagnostic seam) and return
     * (flips, unexplained). A flip is a text slot the frame rule alone turned `withheld`; it is explained
     * only by the builder's EXACT seeding rule on this frame — its canonical value equals a seeded exact
     * value (value-judged on the canonical form, or on the raw form within the cap; or an identity id's
     * text/desc), or it contains a NAME run (from the NAME node's TEXT when non-blank, otherwise its DESC).
     * Anything else is chrome the rule over-withholds (the GG5 / LL1 class). Never records frame text.
     */
    protected fun frameFlips(path: String, platform: Platform, tree: UiNode): Pair<Int, List<String>> {
        val on = (SkeletonBuilder.outcome(tree, null, META, platform, DAY) as? SkeletonBuilder.Outcome.Built)?.skeleton
            ?: return 0 to emptyList()
        val off = (DiagnosticSkeletonBuilder.outcomeWithoutFrameRule(tree, null, META, platform, DAY) as? SkeletonBuilder.Outcome.Built)
            ?.skeleton ?: return 0 to emptyList()
        val seededExact = HashSet<String>()
        val nameRuns = HashSet<String>()
        walkNodes(tree) { n ->
            val kind = CustomerTextMarkers.idMarkerFor(n.viewIdResourceName)?.kind
            n.scrubbableStrings().forEach { (field, v) ->
                if (v.isNullOrBlank()) return@forEach
                val c = canon(v)
                if (PiiShapes.containsMask(c)) return@forEach
                val raw = v.trim()
                if (valueJudged(c) || (raw.length <= 40 && valueJudged(raw))) seededExact += c
                val rendered = field == UiNodeTextField.TEXT || field == UiNodeTextField.CONTENT_DESCRIPTION
                if (rendered && kind?.seedsExactValue == true) seededExact += c
            }
            if (kind != null) {
                nameRunSourceOf(n)?.takeIf { kind.seedsRunsFrom(it) }?.let { nameRuns += letterRuns(it, minLetters = kind.minRunLetters) }
            }
        }
        var flips = 0
        val unexplained = mutableListOf<String>()
        fun walk(n: UiNode, a: UiSkeletonNodeDto, b: UiSkeletonNodeDto) {
            n.scrubbableStrings().forEach { (field, v) ->
                if (v.isNullOrBlank()) return@forEach
                if (a.text[field.wire] == TextSlot.WITHHELD && b.text[field.wire] != TextSlot.WITHHELD) {
                    flips++
                    val c = canon(v)
                    if (c !in seededExact && letterRuns(c).none { it in nameRuns }) {
                        unexplained += "$path: ${field.wire} flipped by the frame rule"
                    }
                }
            }
            n.children.indices.forEach { walk(n.children[it], a.children[it], b.children[it]) }
        }
        walk(tree, on.root, off.root)
        return flips to unexplained
    }

    /** QQ1's third substitution family: a BARE first name in name masks (not caught per field). */
    protected fun barePseudonym(mask: String): String =
        if (mask.startsWith("[redacted") || mask == "[name]") "Jordan" else pseudonym(mask, 0)

    /**
     * Hand-written guard inputs (QQ1): the frame-rule shapes, with RAW pseudonyms, so the guard fires even
     * though the committed corpus (masked) and its pseudonym substitutions produce no frame-rule flips.
     */
    protected val guardFixtures: List<Pair<String, UiNode>> = listOf(
        // AA1: a raw pseudonym repeated beside a `customer_name` (exact + run containment).
        "aa1" to UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.doordash.driverapp:id/contact_row",
            contentDescription = "Jordan",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Jordan"),
                UiNode(className = "android.widget.TextView", text = "Jordan's order"),
                UiNode(className = "android.widget.TextView", text = "Call Jordan"),
                UiNode(className = "android.widget.TextView", text = "Leave at door"),
            ),
        ),
        // PP2: a NAME rendered only in its desc.
        "desc-only-name" to UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.doordash.driverapp:id/contact_row",
            children = listOf(
                UiNode(className = "android.widget.ImageView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", contentDescription = "Morgan"),
                UiNode(className = "android.widget.TextView", text = "Morgan, 2 items"),
                UiNode(className = "android.widget.TextView", text = "Continue"),
            ),
        ),
        // LL1 / PP6: an ADDRESS and an EXACT id seed their exact values only.
        "exact-seeds" to UiNode(
            className = "android.widget.LinearLayout",
            viewIdResourceName = "com.doordash.driverapp:id/sheet",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/address_line_1", text = "Bay View Commons"),
                UiNode(className = "android.widget.TextView", text = "Bay View Commons"),
                UiNode(className = "android.widget.TextView", text = "View details"),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/tvTitle", text = "Riley"),
                UiNode(className = "android.widget.ImageView", contentDescription = "Riley"),
            ),
        ),
    ).map { (name, tree) -> name to tree.restoreParents() }

    /** Two shape-matched pseudonym families per mask kind. */
    protected fun pseudonym(mask: String, variant: Int): String = when {
        mask.startsWith("[redacted") || mask == "[name]" -> if (variant == 0) "Jordan T" else "Morgan K"
        mask == "[address]" -> if (variant == 0) "1425 Sample Ridge Dr" else "3310 Oak Hollow Ln"
        mask == "[email]" -> if (variant == 0) "jt@example.com" else "mk@example.org"
        mask == "[phone]" -> if (variant == 0) "210-555-0100" else "512-555-0199"
        mask == "[card]" -> if (variant == 0) "Visa ••••6222" else "Visa ••••1934"
        mask == "[note]" -> if (variant == 0) "leave it at the door" else "ring the bell twice"
        else -> error(mask)
    }

    protected fun corpusHasNoMask(tree: UiNode): Boolean {
        var found = false
        walkNodes(tree) { n -> if (n.scrubbableStrings().any { (_, v) -> v != null && MASK_TOKEN.containsMatchIn(v) }) found = true }
        return !found
    }

    protected fun diff(a: UiSkeletonDto?, b: UiSkeletonDto?): String {
        if (a == null || b == null) return " (a=${a != null}, b=${b != null})"
        val out = mutableListOf<String>()
        fun walk(x: UiSkeletonNodeDto, y: UiSkeletonNodeDto, p: String) {
            if (x.text != y.text) out += "$p ${x.id}: ${x.text} vs ${y.text}"
            x.children.zip(y.children).forEachIndexed { i, (cx, cy) -> walk(cx, cy, "$p/$i") }
        }
        walk(a.root, b.root, "")
        return ": " + out.take(3).joinToString("; ")
    }

    /**
     * Redact the WHOLE tree the way the corpus intake does (`SnapshotRedactor.redact` over the serialized
     * frame — its replacements are DOCUMENT-WIDE, #1160 review AA1), then walk original / redacted /
     * skeleton in parallel and report every field the redaction changed that still carries an `h`.
     * Returns (rewritten count, decoy-rewritten count, problems).
     */
    protected fun parityProblems(path: String, tree: UiNode, item: UiSkeletonDto): Triple<Int, Int, List<String>> {
        val redacted = UiNodeSchema.deserialize(SnapshotRedactor.redact(UiNodeSchema.serialize(tree)))
        var rewritten = 0
        var decoys = 0
        var exempt = 0
        val problems = mutableListOf<String>()
        // Values the intake propagates document-wide (a `PII_ID_SUFFIXES` id carries them) but the census
        // does NOT seed frame-wide, vs values the census DOES seed from an id (an identity id's rendered
        // text/desc — reviews CC3, EE1). Canonical form, as the builder keys them (review EE2).
        val propagatedNotSeeded = HashSet<String>()
        val idSeeded = HashSet<String>()
        val idRuns = HashSet<String>()
        walkNodes(tree) { n ->
            val id = n.viewIdResourceName
            val kind = CustomerTextMarkers.idMarkerFor(id)?.kind
            // Review AB1: derived from the kind table, never a hand-list — a kind that seeds anything.
            val identity = kind?.seedsExactValue == true
            n.scrubbableStrings().forEach { (field, v) ->
                if (v.isNullOrBlank()) return@forEach
                val canonical = canon(v)
                val seeds = identity && !PiiShapes.containsMask(canonical) &&
                    (field == UiNodeTextField.TEXT || field == UiNodeTextField.CONTENT_DESCRIPTION)
                when {
                    seeds -> idSeeded += canonical
                    CustomerTextMarkers.idMarkerFor(id) != null -> propagatedNotSeeded += canonical
                }
            }
            // Reviews GG1, LL1, NN3, PP2, AC4: only a NAME contributes letter runs (≥2 letters) — from its
            // usable TEXT, otherwise its usable CONTENT_DESCRIPTION (the builder's own rule).
            if (kind != null) {
                nameRunSourceOf(n)?.takeIf { kind.seedsRunsFrom(it) }?.let { idRuns += letterRuns(it, minLetters = kind.minRunLetters) }
            }
        }
        // Review HH3: every canonical key a value predicate caught ANYWHERE in the frame — on the canonical
        // form, or on the raw trimmed form when that is within the cap (the builder's bounded raw pass).
        val judgedKeys = HashSet<String>()
        walkNodes(tree) { n ->
            n.scrubbableStrings().forEach { (_, v) ->
                if (v.isNullOrBlank()) return@forEach
                val raw = v.trim()
                val canonical = canon(v)
                if (valueJudged(canonical) || (raw.length <= 40 && valueJudged(raw))) judgedKeys += canonical
            }
        }
        fun walk(o: UiNode, r: UiNode, s: UiSkeletonNodeDto) {
            val redactedValues = r.scrubbableStrings().toMap()
            for ((field, value) in o.scrubbableStrings()) {
                if (value.isNullOrBlank() || redactedValues[field] == value) continue
                rewritten++
                if (CorpusDecoys.isDecoy(value)) decoys++
                if (s.text[field.wire]?.h == null) continue
                // ADR-0011 §2 frame-level rule (reviews CC3, EE1): a PII id that does not SEED the frame —
                // an intake-only id, a content id, or a non-text field of an identity id — withholds its
                // own field only, so a chrome value it shares with an id-less node may be rewritten
                // document-wide by the intake yet hashed by the census. Exempt exactly that case: the
                // value survives redaction in isolation, and such a field is its ONLY cause (review DD1:
                // no seeding identity-id occurrence and no value-judging step anywhere in the frame).
                val trimmed = canon(value)
                if (redactedInIsolation(o.viewIdResourceName, field.wire, value) == value &&
                    trimmed in propagatedNotSeeded && trimmed !in idSeeded && trimmed !in judgedKeys &&
                    letterRuns(trimmed).none { it in idRuns }
                ) {
                    exempt++
                    continue
                }
                problems += "$path: '${field.wire}' is rewritten by the redactor but hashed"
            }
            check(o.children.size == r.children.size && o.children.size == s.children.size) { "$path: shape drift" }
            o.children.indices.forEach { walk(o.children[it], r.children[it], s.children[it]) }
        }
        walk(tree, redacted, item.root)
        if (exempt > 0) println("$path: $exempt intake-only-id parity exemption(s) (ADR §2, review CC3)")
        return Triple(rewritten, decoys, problems)
    }

    /** The §2 value-judging steps 3–8 through the builder's OWN public predicate (review AJ7). */
    protected fun valueJudged(trimmed: String): Boolean = SkeletonBuilder.judgesValue(trimmed)

    /** The builder's OWN letter-run split (`LetterRuns.letterRuns`, review AH5) — never a re-implementation. */
    protected fun letterRuns(value: String, minLetters: Int = 0): List<String> = LetterRuns.letterRuns(value, minLetters)

    protected fun redactedInIsolation(id: String?, wire: String, value: String): String {
        val json = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(listOfNotNull(id?.let { "id" to JsonPrimitive(it) }, wire to JsonPrimitive(value)).toMap()),
        )
        val out = SnapshotRedactor.redact(json)
        return (Json.parseToJsonElement(out).jsonObject[wire] as JsonPrimitive).content
    }

    protected val piiFragments = listOf(
        "Jordan T", "morgan k.", "José  R", "1425 Sample Ridge Dr", "3310 Oak Hollow Ln", "San Antonio, TX 78254",
        "7610 Fletchers", "Apt 12", "Unit 4B", "Suite 200", "pin 4821", "PIN:0000", "210-555-0100",
        "sam@example.com", "\"leave it at the door\"", "Visa ••••6222", "Gate code 1234",
    )

    protected val chrome = listOf(
        "Accept", "Decline", "Pickup", "Deliver by", "Hand it to me", "Confirm", "is waiting", "Order",
        "Continue", "Arrived", "at the door", "Tap", "items", "\$7.50", "3.2 mi", "&", "→", "Next",
    )

    protected val valueArb: Arb<String> = arbitrary { rs ->
        val parts = mutableListOf<String>()
        repeat(1 + rs.random.nextInt(4)) {
            parts += if (rs.random.nextInt(3) == 0) piiFragments[rs.random.nextInt(piiFragments.size)]
            else chrome[rs.random.nextInt(chrome.size)]
        }
        parts.shuffled(rs.random).joinToString(" ")
    }

    /** Envelope/slot vocabulary an input token may legitimately coincide with. */
    protected val vocab = ("schemaId hashDomain filterRev fingerprint platform platformAppVersion appVersion " +
        "rulesetReleaseTag engineVersion rulesetFormatVersion day windowTitle root isClickable isEnabled " +
        "isChecked text children kind withheld digits mixed words uinode.skeleton.v1 doordash corpus test " +
        "clickLabel 2026-09-30")

    protected fun stripClassAndId(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it != "class" && it != "id" }.mapValues { stripClassAndId(it.value) })
        is JsonArray -> JsonArray(e.map { stripClassAndId(it) })
        else -> e
    }
}
