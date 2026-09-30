package cloud.trotter.dashbuddy.census

import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import cloud.trotter.dashbuddy.domain.capture.ReplayMetadata
import cloud.trotter.dashbuddy.domain.capture.schema.UiNodeSchema
import cloud.trotter.dashbuddy.domain.census.contract.CaseFold
import cloud.trotter.dashbuddy.domain.census.contract.CensusHash
import cloud.trotter.dashbuddy.domain.census.contract.ClassNameGrammar
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
import java.time.LocalDate

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
    private fun platformOf(path: String): Platform {
        val segments = path.substringAfterLast('/').removeSuffix(".json").split("__")
        val token = segments.getOrNull(1)?.takeIf { segments.size >= 3 && it.matches(Regex("[a-z0-9_]+")) }
            ?: return Platform.Unknown
        return Platform.fromWire(token) ?: error("$path: unknown platform wire '$token'")
    }

    private fun build(f: Fixture, tree: UiNode = f.tree): UiSkeletonDto? =
        SkeletonBuilder.build(tree, null, META, platformOf(f.path), DAY)

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

    /** One value's slot through the whole builder (a one-node frame); null when the frame is refused. */
    private fun slotOf(value: String): TextSlot? =
        SkeletonBuilder.build(UiNode(className = "android.widget.TextView", text = value), null, META, Platform.DoorDash, DAY)
            ?.root?.text?.get("text")

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
            val text = builtJson.getValue(f.path)
            val json = Json.parseToJsonElement(text).jsonObject
            if (!ENVELOPE_KEYS.containsAll(json.keys)) problems += "${f.path}: envelope keys ${json.keys - ENVELOPE_KEYS}"
            json["windowTitle"]?.let { slot("${f.path}.windowTitle", it) }
            node("${f.path}.root", json.getValue("root"))
            if (text.contains("bounds")) problems += "${f.path}: bounds"
            if (text.contains("NEVER_IN_A_SKELETON")) problems += "${f.path}: device fingerprint"
        }
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
    }

    // The id grammar gate (ADR-0011 §1, #1160 review AA10) -----------------------------------------

    @Test
    fun `the id gate rejects exactly the dynamic ids in the corpus, and no rejected id is shipped`() {
        val rejected = sortedSetOf<String>()
        corpus.forEach { f ->
            walkNodes(f.tree) { n -> n.viewIdResourceName?.let { if (!SkeletonBuilder.isStaticId(it)) rejected += it } }
        }
        // A static id that trips the gate is a red test here, never a silent drop (review CC7 admitted a
        // single internal space, so `Artwork Image` is static now).
        assertEquals(
            sortedSetOf(
                "PRIMARY_BUTTON_3f488d4a-0f0b-4fb9-9c86-c4e0253ba22a",
                "PRIMARY_BUTTON_62132347-ff07-4f36-988d-db9d3cfa4dbd",
                "PRIMARY_BUTTON_9db4e2af-5a58-4a43-ba63-295126ceddef",
            ),
            rejected,
        )
        built.mapNotNull { it.second }.forEach { item ->
            walkSkeleton(item.root) { n -> n.id?.let { assertTrue(it, SkeletonBuilder.isStaticId(it)) } }
        }
        // Review JJ1: the frame-level containment rule nulls no committed chrome id — no identity seed
        // collides with a static id anywhere in the corpus (every static raw id reaches the wire).
        val frameDropped = sortedSetOf<String>()
        for ((f, item) in built) {
            item ?: continue
            fun pair(n: UiNode, s: UiSkeletonNodeDto) {
                val raw = n.viewIdResourceName
                if (raw != null && SkeletonBuilder.isStaticId(raw) && s.id == null) frameDropped += "${f.path}: $raw"
                n.children.zip(s.children).forEach { (a, b) -> pair(a, b) }
            }
            pair(f.tree, item.root)
        }
        assertEquals(sortedSetOf<String>(), frameDropped)
    }

    @Test
    fun `the class gate rejects no committed class, and no rejected class is shipped (review CC1)`() {
        val rejected = sortedSetOf<String>()
        corpus.forEach { f ->
            walkNodes(f.tree) { n -> n.className?.let { if (!ClassNameGrammar.isStatic(it)) rejected += it } }
        }
        // Empty today: a new dynamic or text-like class in the corpus turns this red, never a silent drop.
        assertEquals(sortedSetOf<String>(), rejected)
        built.mapNotNull { it.second }.forEach { item ->
            walkSkeleton(item.root) { n -> n.className?.let { assertTrue(it, ClassNameGrammar.isStatic(it)) } }
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
        var exempt = 0
        val problems = mutableListOf<String>()
        // Values the intake propagates document-wide (a `PII_ID_SUFFIXES` id carries them) but the census
        // does NOT seed frame-wide, vs values the census DOES seed from an id (an identity id's rendered
        // text/desc — reviews CC3, EE1). Canonical form, as the builder keys them (review EE2).
        val propagatedNotSeeded = HashSet<String>()
        val idSeeded = HashSet<String>()
        walkNodes(tree) { n ->
            val id = n.viewIdResourceName
            val identity = CustomerTextMarkers.idMarkerFor(id)?.valueIsPii == true
            n.scrubbableStrings().forEach { (field, v) ->
                if (v.isNullOrBlank()) return@forEach
                val seeds = identity && (field == UiNodeTextField.TEXT || field == UiNodeTextField.CONTENT_DESCRIPTION)
                when {
                    seeds -> idSeeded += CensusHash.canonical(v)
                    PiiShapes.hasPiiIdSuffix(id) -> propagatedNotSeeded += CensusHash.canonical(v)
                }
            }
        }
        // Review GG1: an identity value also withholds any field CONTAINING one of its ≥3-letter runs.
        val idRuns = idSeeded.flatMap { letterRuns(it, minLetters = 2) }.toSet()
        // Review HH3: every canonical key a value predicate caught ANYWHERE in the frame — on the canonical
        // form, or on the raw trimmed form when that is within the cap (the builder's bounded raw pass).
        val judgedKeys = HashSet<String>()
        walkNodes(tree) { n ->
            n.scrubbableStrings().forEach { (_, v) ->
                if (v.isNullOrBlank()) return@forEach
                val raw = v.trim()
                val canonical = CensusHash.canonical(v)
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
                val trimmed = CensusHash.canonical(value)
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

    /**
     * The §2 value-judging steps 3, 4, 5, 7, 8 through their PUBLIC owners (the builder's
     * `withholdingStep` is internal to `:core:pipeline`) — used only to prove an exemption has no other
     * cause, so a drift here can only make the guard STRICTER (it would refuse an exemption).
     */
    private fun valueJudged(trimmed: String): Boolean =
        CustomerTextMarkers.unredactedMarker(trimmed) != null ||
            PiiShapes.customerLeadIn(trimmed) != null ||
            PiiShapes.containsMask(trimmed) ||
            PiiShapes.hasNameShape(trimmed) ||
            PiiShapes.VALUE_SHAPES.any { it.hits(trimmed) }

    @Test
    fun `(c) full-tree parity holds on the raw-only QUOTED_NOTE value (review FF1)`() {
        val frame = UiNode(
            className = "android.widget.LinearLayout",
            children = listOf(UiNode(className = "android.widget.TextView", text = "\"ab  cd\"")),
        ).restoreParents()
        val item = SkeletonBuilder.build(frame, null, META, Platform.DoorDash, DAY)!!
        assertEquals(TextSlot.WITHHELD, item.root.children.single().text.getValue("text"))
        val (rewritten, _, problems) = parityProblems("ff1", frame, item)
        assertEquals(1, rewritten)
        assertTrue(problems.joinToString(), problems.isEmpty())
    }

    @Test
    fun `(c) negative control - a RAW-only seed elsewhere refuses the exemption (review HH3)`() {
        val frame = UiNode(
            className = "android.widget.LinearLayout",
            text = "\"ab cd\"",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/step_description", text = "\"ab cd\""),
                UiNode(className = "android.widget.TextView", text = "\"ab  cd\""),
            ),
        ).restoreParents()
        val good = SkeletonBuilder.build(frame, null, META, Platform.DoorDash, DAY)!!
        assertEquals(TextSlot.WITHHELD, good.root.text.getValue("text"))
        assertTrue(parityProblems("good", frame, good).third.isEmpty())
        val bad = good.copy(root = good.root.copy(text = mapOf("text" to TextSlot(h = CensusHash.of("\"ab cd\""), kind = "words:2"))))
        val (_, _, problems) = parityProblems("bad", frame, bad)
        assertEquals(problems.toString(), 1, problems.size)
    }

    @Test
    fun `(c) negative control - the exemption refuses a value with an independent ID_MARKERS cause (review DD1)`() {
        val frame = UiNode(
            className = "android.widget.LinearLayout",
            contentDescription = "Sam",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/customer_name", text = "Sam"),
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/step_description", text = "Sam"),
            ),
        ).restoreParents()
        val good = SkeletonBuilder.build(frame, null, META, Platform.DoorDash, DAY)!!
        assertEquals(TextSlot.WITHHELD, good.root.text.getValue("desc"))
        assertTrue(parityProblems("good", frame, good).third.isEmpty())
        // An INCORRECTLY hashed root slot: the intake-only occurrence must not exempt it.
        val bad = good.copy(root = good.root.copy(text = mapOf("desc" to TextSlot(h = CensusHash.of("Sam"), kind = "words:1"))))
        val (_, _, problems) = parityProblems("bad", frame, bad)
        assertEquals(problems.toString(), 1, problems.size)
    }

    /**
     * Maximal Unicode-letter runs, case-folded — the test-side mirror of the builder's private token
     * split (review GG1). Used only to REFUSE an exemption, so a drift can only make the guard stricter.
     */
    private fun letterRuns(value: String, minLetters: Int = 0): List<String> =
        Regex("\\p{L}+").findAll(value)
            .map { it.value }
            .filter { it.codePointCount(0, it.length) >= minLetters } // HH2: letter code points
            .map { CaseFold.fold(it) } // HH1: the one fold the builder uses
            .toList()

    private fun redactedInIsolation(id: String?, wire: String, value: String): String {
        val json = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(listOfNotNull(id?.let { "id" to JsonPrimitive(it) }, wire to JsonPrimitive(value)).toMap()),
        )
        val out = SnapshotRedactor.redact(json)
        return (Json.parseToJsonElement(out).jsonObject[wire] as JsonPrimitive).content
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
        val item = SkeletonBuilder.build(frame, null, META, Platform.DoorDash, DAY)!!
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
        assertTrue(slotOf("Hand it to me: ")?.h != null)
    }

    // (f) ------------------------------------------------------------------------------------------

    @Test
    fun `(f) determinism, canonical JSON and idempotence under the corpus intake`() {
        val problems = mutableListOf<String>()
        for ((f, item) in built) {
            val again = build(f)
            if (item != again) problems += "${f.path}: not deterministic"
            if (item != null) {
                val json = builtJson.getValue(f.path)
                if (SkeletonSchema.serialize(item) != json) problems += "${f.path}: Built.json is not the item's serialization"
                if (SkeletonSchema.serialize(SkeletonSchema.deserialize(json)) != json) problems += "${f.path}: not canonical"
                if (SkeletonSchema.deserialize(json) != item) problems += "${f.path}: does not round-trip"
            }
            // Idempotence: re-running the corpus intake over the fixture never changes its skeleton —
            // everything the intake would rewrite is already withheld.
            val reRedacted = UiNodeSchema.deserialize(SnapshotRedactor.redact(UiNodeSchema.serialize(f.tree)))
            reRedacted.restoreParents()
            val rebuilt = build(f, reRedacted)
            if (rebuilt != item) problems += "${f.path}: re-redaction changed the skeleton${diff(item, rebuilt)}"
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
            // Steps 7/8 judge the raw trimmed value AND the canonical form (reviews EE2, FF1).
            // Review HH5: the raw form counts only within the cap — the builder's raw pass is bounded (GG2).
            val forms = setOfNotNull(value.trim().takeIf { it.length <= 40 }, CensusHash.canonical(value))
            // A sensitive fragment ("Visa ••••…") refuses the whole one-node frame — nothing to check.
            val slot = slotOf(value) ?: return@checkAll
            if (forms.any { form -> shapes.any { it.containsMatchIn(form) } }) {
                piiSeen++
                assertNull("'$value' matches a PiiShapes pattern but hashed", slot.h)
            }
            // No input token appears verbatim outside class/id: build a one-node tree, drop class/id,
            // and look for every token of the input (tokens that are schema vocabulary are skipped).
            val item = SkeletonBuilder.build(
                UiNode(className = "android.widget.TextView", text = value, contentDescription = value, hintText = value),
                value, META, Platform.DoorDash, DAY,
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
        "clickLabel 2026-09-30")

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
