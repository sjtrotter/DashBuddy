package cloud.trotter.dashbuddy.census

import cloud.trotter.census.contract.CensusFingerprint
import cloud.trotter.census.contract.CensusSkeletonSchema
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * #1173 — the app corpus's wire output, replayed by the census server's ingest suite.
 * SENSITIVE/ is excluded entirely: the dasher's sensitive surfaces are never parsed or stored,
 * and production drops those frames before the builder, even when fixture markers were redacted.
 */
/** One pretty-printing codec for the manifest (a per-call `Json { }` is a -Werror warning). */
private val PRETTY_JSON = Json { prettyPrint = true }

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
        val screenLines = exportedOutcomes.map { (fixture, outcome) ->
            val record = buildJsonObject {
                put("file", fixture.path)
                when (outcome) {
                    is SkeletonBuilder.Outcome.Built -> {
                        put("fingerprint", outcome.skeleton.fingerprint)
                        put("hashes", JsonArray(allHashes(outcome.skeleton).distinct().sorted().map { JsonPrimitive(it) }))
                        val skeletonJson = Json.parseToJsonElement(outcome.json).jsonObject
                        // #1174 review (Astra): STRICT round-trip through the wire DTO — an unexpected key or a
                        // plaintext slot anywhere in the skeleton is a decode failure, not a grep blind spot.
                        CensusSkeletonSchema.deserialize(outcome.json)
                        put("skeleton", skeletonJson)
                    }
                    is SkeletonBuilder.Outcome.Refused -> put("refused", outcome.reason.name)
                }
            }
            Json.encodeToString(JsonObject.serializer(), record).also { line ->
                assertFalse("${fixture.path}: redaction marker reached the wire", line.contains("[redacted"))
            }
        }
        val directory = File(RepoRoot.locate(), "census-contract/conformance")
        val notificationLines = File(directory, "notification-vectors.jsonl").readLines()
        assertTrue("synthetic notification vectors must not be empty", notificationLines.isNotEmpty())
        notificationLines.forEach { line ->
            val row = Json.parseToJsonElement(line).jsonObject
            assertTrue("synthetic vector must use the contract/notification namespace", fileKey(row).startsWith("contract/notification/"))
            assertTrue("synthetic vector must be a built notification", row["skeleton"]?.jsonObject?.get("schemaId")?.jsonPrimitive?.content == "notification.skeleton.v1")
        }
        val lines = (screenLines + notificationLines).sortedBy { fileKey(Json.parseToJsonElement(it).jsonObject) }
        val records = lines.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals("duplicate conformance file key", records.size, records.map(::fileKey).toSet().size)
        records.forEach { record ->
            val payload = record["skeleton"]
            if (payload == null) {
                assertEquals("refusals must contain no uploadable payload", setOf("file", "refused"), record.keys)
                assertTrue("refusal reason must not be empty", record.getValue("refused").jsonPrimitive.content.isNotEmpty())
            } else {
                assertEquals(setOf("file", "fingerprint", "hashes", "skeleton"), record.keys)
                val item = CensusSkeletonSchema.deserialize(payload.toString())
                assertEquals("noncanonical wire row: ${fileKey(record)}", payload.toString(), CensusSkeletonSchema.serialize(item))
                assertEquals(item.fingerprint, record.getValue("fingerprint").jsonPrimitive.content)
                assertEquals(item.fingerprint, CensusFingerprint.of(item))
                val hashes = CensusSkeletonSchema.slots(item).mapNotNull { it.h }.distinct().sorted()
                assertEquals(JsonArray(hashes.map { JsonPrimitive(it) }), record.getValue("hashes"))
            }
        }
        assertFalse(
            "SENSITIVE fixtures must never reach the conformance artifact",
            lines.any { Json.parseToJsonElement(it).jsonObject.getValue("file").jsonPrimitive.content.startsWith("SENSITIVE/") },
        )
        val actual = (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
        val golden = File(directory, "skeletons.jsonl.gz")
        val manifestFile = File(directory, "manifest.json")
        val manifest = manifest(records, actual)

        if (System.getProperty("exportCensusGolden") == "true") {
            golden.parentFile?.mkdirs()
            GZIPOutputStream(golden.outputStream()).use { it.write(actual) }
            manifestFile.writeText(PRETTY_JSON.encodeToString(JsonObject.serializer(), manifest) + "\n")
            fail("skeletons.jsonl.gz and manifest.json regenerated — re-run without the flag and review the diff")
        }

        assertTrue("missing ${golden.path} — regenerate with -DexportCensusGolden=true", golden.isFile)
        val approved = GZIPInputStream(golden.inputStream()).use { it.readBytes() }
        assertTrue("missing ${manifestFile.path} — regenerate with -DexportCensusGolden=true", manifestFile.isFile)
        assertEquals("conformance manifest differs — regenerate with -DexportCensusGolden=true", manifest, Json.parseToJsonElement(manifestFile.readText()))
        if (approved.contentEquals(actual)) return

        val approvedLines = approved.toString(Charsets.UTF_8).split('\n')
        val actualLines = actual.toString(Charsets.UTF_8).split('\n')
        val firstDifference = (0 until maxOf(approvedLines.size, actualLines.size)).firstOrNull {
            approvedLines.getOrNull(it) != actualLines.getOrNull(it)
        } ?: 0
        assertArrayEquals(
            "skeletons.jsonl.gz differs at line ${firstDifference + 1} " +
                "(${records.getOrNull(firstDifference)?.let(::fileKey) ?: "end of file"}) — " +
                "regenerate with -DexportCensusGolden=true and review the diff",
            approved,
            actual,
        )
    }

    private fun fileKey(record: JsonObject): String = record.getValue("file").jsonPrimitive.content

    /** Counts distinguish non-uploadable refusals; SHA-256 covers sorted JSONL including its final LF. */
    private fun manifest(records: List<JsonObject>, bytes: ByteArray): JsonObject = buildJsonObject {
        put("formatVersion", 1)
        put("totalRecords", records.size)
        val built = records.mapNotNull { it["skeleton"]?.jsonObject?.getValue("schemaId")?.jsonPrimitive?.content }
        val refused = records.mapNotNull { it["refused"]?.jsonPrimitive?.content }
        put("builtBySchema", buildJsonObject {
            built.groupingBy { it }.eachCount().toSortedMap().forEach { (schema, count) -> put(schema, count) }
        })
        put("refusedByReason", buildJsonObject {
            refused.groupingBy { it }.eachCount().toSortedMap().forEach { (reason, count) -> put(reason, count) }
        })
        put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }
}
