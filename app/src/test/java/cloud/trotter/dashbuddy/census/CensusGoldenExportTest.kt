package cloud.trotter.dashbuddy.census

import cloud.trotter.census.contract.SkeletonSchema
import cloud.trotter.dashbuddy.core.pipeline.SensitiveTextMarkers
import cloud.trotter.dashbuddy.core.pipeline.census.SkeletonBuilder
import cloud.trotter.dashbuddy.guard.RepoRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * #1173 — the app corpus's wire output, replayed by the census server's ingest suite.
 * SENSITIVE/ is excluded entirely: the dasher's sensitive surfaces are never parsed or stored,
 * and production drops those frames before the builder, even when fixture markers were redacted.
 */
class CensusGoldenExportTest : SkeletonCorpusTestBase() {

    @Test
    fun `census output matches the committed conformance golden`() {
        assertTrue("census corpus must not be empty", corpus.isNotEmpty())
        val sortedOutcomes = outcomes.sortedBy { it.first.path }
        // Mirrors SkeletonCorpusTest (d), including the fixtures excluded from the artifact below.
        sortedOutcomes.forEach { (fixture, outcome) ->
            if (fixture.path.startsWith("SENSITIVE/") && SensitiveTextMarkers.findMarker(fixture.tree) != null) {
                assertTrue("${fixture.path}: a marker-bearing SENSITIVE fixture must be refused", outcome is SkeletonBuilder.Outcome.Refused)
            }
        }
        val exportedOutcomes = sortedOutcomes.filterNot { it.first.path.startsWith("SENSITIVE/") }
        val lines = exportedOutcomes.map { (fixture, outcome) ->
            val record = buildJsonObject {
                put("file", fixture.path)
                when (outcome) {
                    is SkeletonBuilder.Outcome.Built -> {
                        put("fingerprint", outcome.skeleton.fingerprint)
                        put("hashes", JsonArray(allHashes(outcome.skeleton).distinct().sorted().map { JsonPrimitive(it) }))
                        val skeletonJson = Json.parseToJsonElement(outcome.json).jsonObject
                        // #1174 review (Astra): STRICT round-trip through the wire DTO — an unexpected key or a
                        // plaintext slot anywhere in the skeleton is a decode failure, not a grep blind spot.
                        SkeletonSchema.deserialize(outcome.json)
                        put("skeleton", skeletonJson)
                    }
                    is SkeletonBuilder.Outcome.Refused -> put("refused", outcome.reason.name)
                }
            }
            Json.encodeToString(JsonObject.serializer(), record).also { line ->
                assertFalse("${fixture.path}: redaction marker reached the wire", line.contains("[redacted"))
            }
        }
        assertFalse(
            "SENSITIVE fixtures must never reach the conformance artifact",
            lines.any { Json.parseToJsonElement(it).jsonObject.getValue("file").jsonPrimitive.content.startsWith("SENSITIVE/") },
        )
        val actual = (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        val golden = File(RepoRoot.locate(), "census-contract/conformance/skeletons.jsonl.gz")

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
                "(${exportedOutcomes.getOrNull(firstDifference)?.first?.path ?: "end of file"}) — " +
                "regenerate with -DexportCensusGolden=true and review the diff",
            approved,
            actual,
        )
    }
}
