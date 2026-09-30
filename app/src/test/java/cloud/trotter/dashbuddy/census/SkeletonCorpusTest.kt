package cloud.trotter.dashbuddy.census

import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.ResourceIdGrammar
import cloud.trotter.dashbuddy.domain.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.domain.census.contract.TextSlot
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonDto
import cloud.trotter.dashbuddy.domain.census.contract.UiSkeletonNodeDto
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNode
import cloud.trotter.dashbuddy.domain.model.accessibility.UiNodeTextField
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

/**
 * ADR-0011 §7 — the census skeleton over the ENTIRE committed corpus (every category folder, incl.
 * `SENSITIVE/`, `UNKNOWN/negative/` and the session-replay frames; NOT the gitignored `INBOX/` and
 * flat `UNKNOWN/` staging areas), asserting (a)–(f) exactly as the ADR writes them, plus the seeded
 * property (#878).
 *
 * The fixtures are MASKED captures, which is a superset condition (every mask is caught by step 5),
 * not the runtime shape; (b) and (c) are what make the corpus load-bearing: (b) puts raw
 * pseudonyms back INTO the mask slots, and (c) runs on the committed decoy fixtures, which carry raw
 * hand-written pseudonyms.
 */
class SkeletonCorpusTest {

    private companion object {
        /** #878 pinned seed — pins PR CI. Bump deliberately to explore new samples. */
        const val SEED = 0x1145_0001L

        const val DAY = "2026-09-30"

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

        val MASK_TOKEN = Regex("""\[(?:redacted(?::[0-9a-f]{4})?|address|email|phone|card|note|name)\]""")
    }

    private data class Fixture(val path: String, val tree: UiNode)

    private val snapshotsDir = File("src/test/resources/snapshots")

    /** Every committed corpus tree, loaded strictly (a committed file that fails to parse fails). */
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
                loadTree(file)?.let { Fixture(rel, it) }
            }
        }
    }

    /**
     * A screen envelope / bare tree / legacy wrapper decodes to its tree; a CLICK envelope to its
     * clicked node (a tree too); a NOTIFICATION envelope has no tree and is skipped (the census
     * never sees notifications in v1, ADR §8).
     */
    private fun loadTree(file: File): UiNode? {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
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
    private fun platformOf(path: String): String {
        val segments = path.substringAfterLast('/').removeSuffix(".json").split("__")
        val token = segments.getOrNull(1)?.takeIf { segments.size >= 3 && it.matches(Regex("[a-z0-9_]+")) }
            ?: return Platform.Unknown.wire
        return (Platform.fromWire(token) ?: error("$path: unknown platform wire '$token'")).wire
    }

    private fun build(f: Fixture, tree: UiNode = f.tree): UiSkeletonDto? =
        SkeletonBuilder.build(tree, null, META, platformOf(f.path), DAY)

    private val built: List<Pair<Fixture, UiSkeletonDto?>> by lazy { corpus.map { it to build(it) } }

    private fun walkNodes(node: UiNode, visit: (UiNode) -> Unit) {
        visit(node)
        node.children.forEach { walkNodes(it, visit) }
    }

    private fun walkSkeleton(node: UiSkeletonNodeDto, visit: (UiSkeletonNodeDto) -> Unit) {
        visit(node)
        node.children.forEach { walkSkeleton(it, visit) }
    }

    private fun allHashes(item: UiSkeletonDto): List<String> {
        val out = mutableListOf<String>()
        item.windowTitle?.h?.let(out::add)
        walkSkeleton(item.root) { n -> n.text.values.mapNotNullTo(out) { it.h } }
        return out
    }

    /** Rewrite every scrubbable string of every node (the #835 SSOT), keeping structure. */
    private fun mapTree(node: UiNode, transform: (String?) -> String?): UiNode =
        node.mapScrubbableStrings(transform).copy(children = node.children.map { mapTree(it, transform) })

    @Test
    fun `the corpus is non-trivial and includes the sensitive, negative and session folders`() {
        assertTrue("corpus size ${corpus.size}", corpus.size >= 800)
        assertTrue(corpus.any { it.path.startsWith("SENSITIVE/") })
        assertTrue(corpus.any { it.path.startsWith("UNKNOWN/negative/") })
        assertTrue(corpus.any { it.path.startsWith("sessions/") })
        assertTrue(corpus.none { it.path.startsWith("INBOX/") })
        assertTrue(built.count { it.second != null } >= 700)
    }

    // (a) ------------------------------------------------------------------------------------------

    @Test
    fun `(a) no string field outside the allowlist and no bounds on any node`() {
        val problems = mutableListOf<String>()
        val wireKeys = UiNodeTextField.entries.map { it.wire }.toSet()
        fun slot(path: String, e: JsonElement) {
            val o = e as? JsonObject ?: return run { problems += "$path: slot is not an object" }
            if (!SLOT_KEYS.containsAll(o.keys)) problems += "$path: slot keys ${o.keys}"
        }
        fun node(path: String, e: JsonElement) {
            val o = e as JsonObject
            if (!NODE_KEYS.containsAll(o.keys)) problems += "$path: node keys ${o.keys - NODE_KEYS}"
            o.forEach { (k, v) ->
                if (v is JsonPrimitive && v.isString && k !in NODE_STRING_KEYS) problems += "$path.$k is a string"
            }
            (o["text"] as? JsonObject)?.forEach { (k, v) ->
                if (k !in wireKeys) problems += "$path.text key '$k' is not a UiNodeTextField wire key"
                slot("$path.text.$k", v)
            }
            (o["children"] as? JsonArray)?.forEachIndexed { i, c -> node("$path[$i]", c) }
        }
        for ((f, item) in built) {
            item ?: continue
            val json = Json.parseToJsonElement(SkeletonSchema.serialize(item)).jsonObject
            if (!ENVELOPE_KEYS.containsAll(json.keys)) problems += "${f.path}: envelope keys ${json.keys - ENVELOPE_KEYS}"
            json["windowTitle"]?.let { slot("${f.path}.windowTitle", it) }
            node("${f.path}.root", json.getValue("root"))
            if (SkeletonSchema.serialize(item).contains("bounds")) problems += "${f.path}: bounds"
            if (SkeletonSchema.serialize(item).contains("NEVER_IN_A_SKELETON")) problems += "${f.path}: device fingerprint"
        }
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
    }

    // The id grammar gate (ADR-0011 §1, #1160 review AA10) -----------------------------------------

    @Test
    fun `the id gate rejects exactly the dynamic ids in the corpus, and no rejected id is shipped`() {
        val rejected = sortedSetOf<String>()
        corpus.forEach { f ->
            walkNodes(f.tree) { n -> n.viewIdResourceName?.let { if (!ResourceIdGrammar.isStatic(it)) rejected += it } }
        }
        // A static id that trips the gate is a red test here, never a silent drop. `Artwork Image`
        // carries a SPACE — it reads as text, so it is treated as absent by design.
        assertEquals(
            sortedSetOf(
                "Artwork Image",
                "PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a",
                "PRIMARY_BUTTON_62132347-ff07-4f36-988d-db9d3cfa4dbd",
                "PRIMARY_BUTTON_9db4e2af-5a58-4a43-ba63-295126ceddef",
            ),
            rejected,
        )
        built.mapNotNull { it.second }.forEach { item ->
            walkSkeleton(item.root) { n -> n.id?.let { assertTrue(it, ResourceIdGrammar.isStatic(it)) } }
        }
    }

    // (b) ------------------------------------------------------------------------------------------

    /** Two shape-matched pseudonym families per mask kind. */
    private fun pseudonym(mask: String, variant: Int): String = when {
        mask.startsWith("[redacted") || mask == "[name]" -> if (variant == 0) "Jordan T" else "Morgan K"
        mask == "[address]" -> if (variant == 0) "1425 Sample Ridge Dr" else "3310 Oak Hollow Ln"
        mask == "[email]" -> if (variant == 0) "jt@example.com" else "mk@example.org"
        mask == "[phone]" -> if (variant == 0) "210-555-0100" else "512-555-0199"
        mask == "[card]" -> if (variant == 0) "Visa ••••6222" else "Visa ••••1934"
        mask == "[note]" -> if (variant == 0) "leave it at the door" else "ring the bell twice"
        else -> error(mask)
    }

    @Test
    fun `(b) pseudonym invariance under two shape-matched substitutions into the mask slots`() {
        val problems = mutableListOf<String>()
        var substitutedFixtures = 0
        for ((f, original) in built) {
            if (corpusHasNoMask(f.tree)) continue
            substitutedFixtures++
            val v0 = build(f, mapTree(f.tree) { s -> s?.let { MASK_TOKEN.replace(it) { m -> pseudonym(m.value, 0) } } })
            val v1 = build(f, mapTree(f.tree) { s -> s?.let { MASK_TOKEN.replace(it) { m -> pseudonym(m.value, 1) } } })
            if (v0 != v1) problems += "${f.path}: the two pseudonym substitutions differ${diff(v0, v1)}"
            // The substituted slots are caught, so the skeleton is the masked fixture's own — unless the
            // substitution made the frame sensitive (a card pseudonym carries "Visa"), which refuses both.
            if (v0 != null && v0 != original) problems += "${f.path}: substitution changed the skeleton${diff(original, v0)}"
        }
        assertTrue("substituted fixtures $substitutedFixtures", substitutedFixtures >= 100)
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
    }

    private fun corpusHasNoMask(tree: UiNode): Boolean {
        var found = false
        walkNodes(tree) { n -> if (n.scrubbableStrings().any { (_, v) -> v != null && MASK_TOKEN.containsMatchIn(v) }) found = true }
        return !found
    }

    private fun diff(a: UiSkeletonDto?, b: UiSkeletonDto?): String {
        if (a == null || b == null) return " (a=${a != null}, b=${b != null})"
        val out = mutableListOf<String>()
        fun walk(x: UiSkeletonNodeDto, y: UiSkeletonNodeDto, p: String) {
            if (x.text != y.text) out += "$p ${x.id}: ${x.text} vs ${y.text}"
            x.children.zip(y.children).forEachIndexed { i, (cx, cy) -> walk(cx, cy, "$p/$i") }
        }
        walk(a.root, b.root, "")
        return ": " + out.take(3).joinToString("; ")
    }

    // (c) ------------------------------------------------------------------------------------------

    /**
     * Redact the WHOLE tree the way the corpus intake does (`SnapshotRedactor.redact` over the serialized
     * frame — its replacements are DOCUMENT-WIDE, #1160 review AA1), then walk original / redacted /
     * skeleton in parallel and report every field the redaction changed that still carries an `h`.
     * Returns (rewritten count, decoy-rewritten count, problems).
     */
    private fun parityProblems(path: String, tree: UiNode, item: UiSkeletonDto): Triple<Int, Int, List<String>> {
        val redacted = UiNodeSchema.deserialize(SnapshotRedactor.redact(UiNodeSchema.serialize(tree)))
        var rewritten = 0
        var decoys = 0
        val problems = mutableListOf<String>()
        fun walk(o: UiNode, r: UiNode, s: UiSkeletonNodeDto) {
            val redactedValues = r.scrubbableStrings().toMap()
            for ((field, value) in o.scrubbableStrings()) {
                if (value.isNullOrBlank() || redactedValues[field] == value) continue
                rewritten++
                if (CorpusDecoys.isDecoy(value)) decoys++
                if (s.text[field.wire]?.h != null) problems += "$path: '${field.wire}' is rewritten by the redactor but hashed"
            }
            check(o.children.size == r.children.size && o.children.size == s.children.size) { "$path: shape drift" }
            o.children.indices.forEach { walk(o.children[it], r.children[it], s.children[it]) }
        }
        walk(tree, redacted, item.root)
        return Triple(rewritten, decoys, problems)
    }

    @Test
    fun `(c) redactor parity - any value the FULL-TREE redaction rewrites has no h`() {
        val problems = mutableListOf<String>()
        var rewritten = 0
        var decoyRewritten = 0
        for ((f, item) in built) {
            item ?: continue
            val (r, d, p) = parityProblems(f.path, f.tree, item)
            rewritten += r
            decoyRewritten += d
            problems += p
        }
        assertTrue("rewritten $rewritten", rewritten > 0)
        assertTrue("load-bearing on the decoy fixtures: $decoyRewritten", decoyRewritten > 0)
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `(c) full-tree parity holds on the duplicate-value fixture (review AA1)`() {
        // The parent repeats the child's customer name; only the child's id marks it. The redactor masks
        // BOTH (document-wide), so the census must withhold both.
        val frame = UiNode(
            className = "android.widget.LinearLayout",
            contentDescription = "Sam",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Sam"),
            ),
        ).restoreParents()
        val item = SkeletonBuilder.build(frame, null, META, "doordash", DAY)!!
        val (rewritten, _, problems) = parityProblems("synthetic", frame, item)
        assertEquals(2, rewritten)
        assertTrue(problems.joinToString(), problems.isEmpty())
    }

    // (d) ------------------------------------------------------------------------------------------

    @Test
    fun `(d) every SENSITIVE fixture the markers catch yields no skeleton`() {
        val sensitive = built.filter { it.first.path.startsWith("SENSITIVE/") }
        val caught = sensitive.filter { SensitiveTextMarkers.findMarker(it.first.tree) != null }
        assertTrue("caught ${caught.size} of ${sensitive.size}", caught.size >= 10)
        caught.forEach { (f, item) -> assertNull("${f.path} must yield no skeleton", item) }
    }

    // (e) ------------------------------------------------------------------------------------------

    @Test
    fun `(e) no h equals the census hash of a PII-valued decoy or of a mask token`() {
        val hashes = built.mapNotNull { it.second }.flatMap { allHashes(it) }.toSet()
        assertTrue(hashes.isNotEmpty())
        val corpusMasks = mutableSetOf<String>()
        corpus.forEach { f ->
            walkNodes(f.tree) { n ->
                n.scrubbableStrings().forEach { (_, v) -> v?.let { MASK_TOKEN.findAll(it).forEach { m -> corpusMasks += m.value } } }
            }
        }
        val piiDecoys = CorpusDecoys.ALLOWED.keys - CHROME_DECOYS
        val forbidden = piiDecoys + PiiShapes.MASK_LITERALS + corpusMasks + SnapshotRedactor.MASK + "\"[note]\""
        val hits = forbidden.filter { CensusHash.of(it) in hashes }
        assertTrue("hashed PII decoy / mask token(s): $hits", hits.isEmpty())
        // Sanity: the chrome decoy is legitimately hashable (it is a label, not PII).
        assertTrue(SkeletonBuilder.slotFor("Hand it to me: ", null)?.h != null)
    }

    // (f) ------------------------------------------------------------------------------------------

    @Test
    fun `(f) determinism, canonical JSON and idempotence under the corpus intake`() {
        val problems = mutableListOf<String>()
        for ((f, item) in built) {
            val again = build(f)
            if (item != again) problems += "${f.path}: not deterministic"
            if (item != null) {
                val json = SkeletonSchema.serialize(item)
                if (SkeletonSchema.serialize(SkeletonSchema.deserialize(json)) != json) problems += "${f.path}: not canonical"
                if (SkeletonSchema.deserialize(json) != item) problems += "${f.path}: does not round-trip"
            }
            // Idempotence: re-running the corpus intake over the fixture never changes its skeleton —
            // everything the intake would rewrite is already withheld.
            val reRedacted = UiNodeSchema.deserialize(SnapshotRedactor.redact(UiNodeSchema.serialize(f.tree)))
            reRedacted.restoreParents()
            if (build(f, reRedacted) != item) problems += "${f.path}: re-redaction changed the skeleton${diff(item, build(f, reRedacted))}"
        }
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
    }

    // Seeded property (#878) -------------------------------------------------------------------------

    private val piiFragments = listOf(
        "Jordan T", "morgan k.", "José  R", "1425 Sample Ridge Dr", "3310 Oak Hollow Ln", "San Antonio, TX 78254",
        "7610 Fletchers", "Apt 12", "Unit 4B", "Suite 200", "pin 4821", "PIN:0000", "210-555-0100",
        "sam@example.com", "\"leave it at the door\"", "Visa ••••6222", "Gate code 1234",
    )
    private val chrome = listOf(
        "Accept", "Decline", "Pickup", "Deliver by", "Hand it to me", "Confirm", "is waiting", "Order",
        "Continue", "Arrived", "at the door", "Tap", "items", "\$7.50", "3.2 mi", "&", "→", "Next",
    )

    private val valueArb: Arb<String> = arbitrary { rs ->
        val parts = mutableListOf<String>()
        repeat(1 + rs.random.nextInt(4)) {
            parts += if (rs.random.nextInt(3) == 0) piiFragments[rs.random.nextInt(piiFragments.size)]
            else chrome[rs.random.nextInt(chrome.size)]
        }
        parts.shuffled(rs.random).joinToString(" ")
    }

    @Test
    fun `property - no PiiShapes match ever hashes, and no input token leaves verbatim`() = runTest {
        // Substring mode throughout, as steps 7/8 run them (the anchored name pattern's containsMatchIn
        // on a trimmed value IS its whole-value match — exactly `PiiShapes.hasNameShape`'s first arm).
        val shapes: List<Regex> = PiiShapes.VALUE_SHAPES.map { it.regex } +
            PiiShapes.FIRST_LAST_INITIAL + PiiShapes.FIRST_LAST_INITIAL_EMBEDDED_REGEX
        var piiSeen = 0
        checkAll(PropSeeds.samples(500), PropSeeds.config(SEED), valueArb) { value ->
            val trimmed = value.trim()
            val slot = SkeletonBuilder.slotFor(value, null)!!
            if (shapes.any { it.containsMatchIn(trimmed) }) {
                piiSeen++
                assertNull("'$value' matches a PiiShapes pattern but hashed", slot.h)
            }
            // No input token appears verbatim outside class/id: build a one-node tree, drop class/id,
            // and look for every token of the input (tokens that are schema vocabulary are skipped).
            val item = SkeletonBuilder.build(
                UiNode(className = "android.widget.TextView", text = value, contentDescription = value, hintText = value),
                value, META, "doordash", DAY,
            ) ?: return@checkAll
            val stripped = stripClassAndId(Json.parseToJsonElement(SkeletonSchema.serialize(item))).toString()
            val vocabulary = stripped.replace(Regex("\"h\":\"[0-9a-f]{16}\""), "")
            value.split(' ').filter { it.length >= 3 && it.any(Char::isUpperCase) }.forEach { token ->
                if (vocab.contains(token)) return@forEach
                assertTrue("'$token' left verbatim in $stripped", !vocabulary.contains(token))
            }
        }
        assertTrue("the property must exercise PII-shaped inputs ($piiSeen)", piiSeen > 50)
    }

    /** Envelope/slot vocabulary an input token may legitimately coincide with. */
    private val vocab = ("schemaId hashDomain filterRev fingerprint platform platformAppVersion appVersion " +
        "rulesetReleaseTag engineVersion rulesetFormatVersion day windowTitle root isClickable isEnabled " +
        "isChecked text children kind withheld digits mixed words uinode.skeleton.v1 doordash corpus test " +
        "clickLabel " + DAY)

    private fun stripClassAndId(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it != "class" && it != "id" }.mapValues { stripClassAndId(it.value) })
        is JsonArray -> JsonArray(e.map { stripClassAndId(it) })
        else -> e
    }

    @Test
    fun `sanity - the TextSlot constant the corpus relies on`() {
        assertEquals("withheld", TextSlot.WITHHELD.kind)
        assertNull(TextSlot.WITHHELD.h)
    }
}
