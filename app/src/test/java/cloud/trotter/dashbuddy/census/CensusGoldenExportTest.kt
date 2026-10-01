package cloud.trotter.dashbuddy.census

import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.census.contract.TextSlot
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** #1173 — the app corpus's wire output, replayed by the census server's ingest suite. */
class CensusGoldenExportTest : SkeletonCorpusTestBase() {

    @Test
    fun `census output matches the committed conformance golden`() {
        assertTrue("census corpus must not be empty", corpus.isNotEmpty())
        val lines = outcomes.sortedBy { (fixture, _) -> fixture.path }.map { (fixture, outcome) ->
            val record = buildJsonObject {
                put("file", fixture.path)
                when (outcome) {
                    is SkeletonBuilder.Outcome.Built -> {
                        put("fingerprint", outcome.skeleton.fingerprint)
                        put("hashes", JsonArray(allHashes(outcome.skeleton).distinct().sorted().map { JsonPrimitive(it) }))
                        put("skeleton", Json.parseToJsonElement(outcome.json).jsonObject)
                    }
                    is SkeletonBuilder.Outcome.Refused -> put("refused", outcome.reason.name)
                }
            }
            // Mirrors SkeletonCorpusTest (d): a SENSITIVE fixture the MARKERS catch yields no skeleton. The
            // committed SENSITIVE/ fixtures are hand-redacted (CLAUDE.md § Snapshot Regression Testing), so one
            // whose markers were redacted away legitimately builds a hash-only skeleton — in production such a
            // frame never reaches the builder (its RULE drops it at the content gate).
            if (fixture.path.startsWith("SENSITIVE/") && SensitiveTextMarkers.findMarker(fixture.tree) != null) {
                assertTrue("${fixture.path}: a marker-bearing SENSITIVE fixture must be refused", record.containsKey("refused"))
                assertFalse("${fixture.path}: a marker-bearing SENSITIVE fixture must never export a skeleton", record.containsKey("skeleton"))
            }
            assertNoPlaintextText(fixture.path, record)
            Json.encodeToString(JsonObject.serializer(), record).also { line ->
                assertFalse("${fixture.path}: redaction marker reached the wire", line.contains("[redacted"))
            }
        }
        val actual = (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        val golden = File(locateRepoRoot(), "census-contract/conformance/skeletons.jsonl.gz")

        if (System.getProperty("exportCensusGolden") == "true") {
            golden.parentFile?.mkdirs()
            GZIPOutputStream(golden.outputStream()).use { it.write(actual) }
            fail("skeletons.jsonl.gz regenerated — re-run without the flag and review the diff")
        }

        assertTrue("missing ${golden.path} — regenerate with -DexportCensusGolden=true", golden.isFile)
        val approved = GZIPInputStream(golden.inputStream()).use { it.readBytes() }
        if (approved.contentEquals(actual)) return

        val approvedLines = approved.toString(Charsets.UTF_8).split('\n')
        val actualLines = actual.toString(Charsets.UTF_8).split('\n')
        val firstDifference = (0 until maxOf(approvedLines.size, actualLines.size)).firstOrNull {
            approvedLines.getOrNull(it) != actualLines.getOrNull(it)
        } ?: 0
        assertArrayEquals(
            "skeletons.jsonl.gz differs at line ${firstDifference + 1} " +
                "(${outcomes.sortedBy { it.first.path }.getOrNull(firstDifference)?.first?.path ?: "end of file"}) — " +
                "regenerate with -DexportCensusGolden=true and review the diff",
            approved,
            actual,
        )
    }

    /**
     * The wire already has `text` keys: node maps and their `text` TextSlot entry. Both must be
     * objects of validated slots, never plaintext values; forbidding the key would reject v1.
     */
    private fun assertNoPlaintextText(path: String, element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key == "text") {
                    assertTrue("$path: plaintext text value reached the wire", value is JsonObject)
                    val obj = value.jsonObject
                    val slots = if ("kind" in obj) listOf(obj) else obj.values
                    slots.forEach { slot ->
                        // Strict decoding rejects unknown/plaintext fields and validates hash/kind.
                        SkeletonSchema.json.decodeFromJsonElement(TextSlot.serializer(), slot)
                    }
                }
                assertNoPlaintextText(path, value)
            }
            is JsonArray -> element.forEach { assertNoPlaintextText(path, it) }
            is JsonPrimitive -> Unit
        }
    }

    private fun locateRepoRoot(): File {
        var dir = File(".").absoluteFile.normalize()
        while (true) {
            if (File(dir, "settings.gradle.kts").isFile) return dir
            dir = dir.parentFile ?: error("Could not locate repo root (settings.gradle.kts)")
        }
    }
}
