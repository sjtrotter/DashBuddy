package cloud.trotter.dashbuddy.census
import cloud.trotter.dashbuddy.core.pipeline.CustomerTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import cloud.trotter.dashbuddy.core.pipeline.census.diagnostics.DiagnosticSkeletonBuilder
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
class SkeletonCorpusTest : SkeletonCorpusTestBase() {

    @Test
    fun `the corpus is non-trivial and includes the sensitive, negative and session folders`() {
        assertTrue("corpus size ${corpus.size}", corpus.size >= 800)
        assertTrue(corpus.any { it.path.startsWith("SENSITIVE/") })
        assertTrue(corpus.any { it.path.startsWith("UNKNOWN/negative/") })
        assertTrue(corpus.any { it.path.startsWith("sessions/") })
        assertTrue(corpus.none { it.path.startsWith("INBOX/") })
        assertTrue(built.count { it.second != null } >= 700)
    }

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

    @Test
    fun `(c) negative control - a user_name that stops seeding is reported, never exempted (review AB1)`() {
        val frame = UiNode(
            className = "android.widget.LinearLayout",
            children = listOf(
                UiNode(className = "android.widget.TextView", viewIdResourceName = "com.doordash.driverapp:id/user_name", text = "Riley"),
                UiNode(className = "android.widget.TextView", text = "Riley"),
            ),
        ).restoreParents()
        val good = SkeletonBuilder.build(frame, null, META, Platform.DoorDash, DAY)!!
        assertEquals(TextSlot.WITHHELD, good.root.children[1].text.getValue("text"))
        assertTrue(parityProblems("good", frame, good).third.isEmpty())
        // The regression: the frame rule no longer seeds `user_name`, so its id-less duplicate hashes. The
        // mirror derives "seeds" from the kind table, so PERSON_OR_MERCHANT is never exempted.
        val bad = (DiagnosticSkeletonBuilder.outcomeWithoutFrameRule(frame, null, META, Platform.DoorDash, DAY)
            as SkeletonBuilder.Outcome.Built).skeleton
        val (_, _, problems) = parityProblems("bad", frame, bad)
        assertEquals(problems.toString(), 1, problems.size)
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

    @Test
    fun `(d) every SENSITIVE fixture the markers catch yields no skeleton`() {
        val sensitive = built.filter { it.first.path.startsWith("SENSITIVE/") }
        val caught = sensitive.filter { SensitiveTextMarkers.findMarker(it.first.tree) != null }
        assertTrue("caught ${caught.size} of ${sensitive.size}", caught.size >= 10)
        caught.forEach { (f, item) -> assertNull("${f.path} must yield no skeleton", item) }
    }

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
            val forms = setOfNotNull(value.trim().takeIf { it.length <= 40 }, canon(value))
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

    @Test
    fun `sanity - the TextSlot constant the corpus relies on`() {
        assertEquals("withheld", TextSlot.WITHHELD.kind)
        assertNull(TextSlot.WITHHELD.h)
    }
}
